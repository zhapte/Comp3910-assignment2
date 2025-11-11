package com.corejsf;



import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import javax.sql.DataSource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;


@Named("timeSheetRepo")
@ApplicationScoped
public class TimeSheetRepo implements TimesheetCollection, Serializable {

    @Inject
    private CurrentUser currentUser;

    @Resource(lookup = "java:jboss/datasources/timesheetsDS")
    private DataSource ds;

    /** Keep DB ids without changing your model classes. */
    private final Map<Timesheet, Long> timesheetIds = new WeakHashMap<>();
    private final Map<TimesheetRow, Long> rowIds = new WeakHashMap<>();

    @PostConstruct
    public void startup() {
        ensureAdminExists();
    }

    // ---------------- TimesheetCollection API ----------------

    @Override
    public List<Timesheet> getTimesheets() {
        final String sql = """
            SELECT t.timesheet_id, t.employee_id, t.end_date, t.overtime_deci, t.flextime_deci
            FROM timesheets t
            ORDER BY t.employee_id, t.end_date DESC
        """;
        List<Timesheet> result = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Timesheet ts = materializeTimesheet(rs);
                loadRows(c, ts);
                result.add(ts);
            }
        } catch (SQLException e) {
            throw new RuntimeException("getTimesheets() failed", e);
        }
        return result;
    }

    @Override
    public List<Timesheet> getTimesheets(final Employee e) {
        if (e == null) return Collections.emptyList();
        final String sql = """
            SELECT t.timesheet_id, t.employee_id, t.end_date, t.overtime_deci, t.flextime_deci
            FROM timesheets t
            WHERE t.employee_id = ?
            ORDER BY t.end_date DESC
        """;
        List<Timesheet> result = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, requireEmployeeId(c, e));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timesheet ts = materializeTimesheet(rs, e);
                    loadRows(c, ts);
                    result.add(ts);
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException("getTimesheets(Employee) failed", ex);
        }
        return result;
    }

    @Override
    public Timesheet getCurrentTimesheet(final Employee e) {
        if (e == null) return null;

		// 1) Try exact match for this week's Friday, prefer newest created
		LocalDate thisFriday = LocalDate.now().with(java.time.DayOfWeek.FRIDAY);
		final String sqlExact = """
			SELECT t.timesheet_id, t.employee_id, t.end_date, t.overtime_deci, t.flextime_deci
			FROM timesheets t
			WHERE t.employee_id = ? AND t.end_date = ?
			ORDER BY t.created_at DESC, t.timesheet_id DESC
			LIMIT 1
		""";
	
		try (Connection c = ds.getConnection();
			PreparedStatement ps1 = c.prepareStatement(sqlExact)) {
	
			ps1.setLong(1, requireEmployeeId(c, e));
			ps1.setDate(2, java.sql.Date.valueOf(thisFriday));
	
			try (ResultSet rs = ps1.executeQuery()) {
				if (rs.next()) {
					Timesheet ts = materializeTimesheet(rs, e);
					loadRows(c, ts);
					return ts; // Found a sheet for this Friday; return newest-created
				}
			}
	
			// 2) Fallback: closest to today (your original ordering)
			final String sqlClosest = """
				SELECT t.timesheet_id, t.employee_id, t.end_date, t.overtime_deci, t.flextime_deci
				FROM timesheets t
				WHERE t.employee_id = ?
				ORDER BY ABS(DATEDIFF(t.end_date, CURDATE())),
						CASE WHEN t.end_date < CURDATE() THEN 1 ELSE 0 END,
						t.end_date DESC
				LIMIT 1
			""";
	
			try (PreparedStatement ps2 = c.prepareStatement(sqlClosest)) {
				ps2.setLong(1, requireEmployeeId(c, e));
				try (ResultSet rs2 = ps2.executeQuery()) {
					if (!rs2.next()) return null;
					Timesheet ts = materializeTimesheet(rs2, e);
					loadRows(c, ts);
					return ts;
				}
			}

    } catch (SQLException ex) {
        throw new RuntimeException("getCurrentTimesheet(Employee) failed", ex);
    }
    }

    @Override
    public String addTimesheet() {
        Employee me = currentUser.getEmployee();
        if (me == null) return "no-user";

        LocalDate endOfWeek = endOfWeekFriday(LocalDate.now());

        final String insertTs = """
            INSERT INTO timesheets (employee_id, end_date, overtime_deci, flextime_deci)
            VALUES (?, ?, 0, 0)
        """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(insertTs, Statement.RETURN_GENERATED_KEYS)) {

            long empId = requireEmployeeId(c, me);
            ps.setLong(1, empId);
            ps.setDate(2, java.sql.Date.valueOf(endOfWeek));
            ps.executeUpdate();

            long tsId;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                tsId = keys.getLong(1);
            }

            Timesheet ts = new Timesheet(me, endOfWeek);
            ts.setOvertime(0);
            ts.setFlextime(0);
            timesheetIds.put(ts, tsId);

            for (int i = 0; i < 5; i++) {
                TimesheetRow row = new TimesheetRow();
                row.setProjectId(0);
                row.setWorkPackageId("");
                row.setHours(new float[]{0, 0, 0, 0, 0, 0, 0});
                ts.getDetails().add(row);
                insertRow(c, ts, i + 1, row); // line_no is 1-based
            }

            return "created";
        } catch (SQLException ex) {
            throw new RuntimeException("addTimesheet() failed", ex);
        }
    }

    public Timesheet getMyCurrentTimesheet() {
        return getCurrentTimesheet(currentUser.getEmployee());
    }

    public List<Timesheet> getMyTimesheets() {
        return getTimesheets(currentUser.getEmployee());
    }

    public void save(final Timesheet ts) {
        if (ts == null) return;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                Long existingId = timesheetIds.get(ts);
                if (existingId == null) {
                    // Insert new header
                    long empId = requireEmployeeId(c, ts.getEmployee());
                    final String ins = """
                        INSERT INTO timesheets (employee_id, end_date, overtime_deci, flextime_deci)
                        VALUES (?, ?, ?, ?)
                    """;
                    try (PreparedStatement ps = c.prepareStatement(ins, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setLong(1, empId);
                        LocalDate end = (ts.getEndDate() != null) ? ts.getEndDate() : endOfWeekFriday(LocalDate.now());
                        ps.setDate(2, java.sql.Date.valueOf(end));
                        ps.setInt(3, 0); // no getters available on your model
                        ps.setInt(4, 0);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            keys.next();
                            existingId = keys.getLong(1);
                            timesheetIds.put(ts, existingId);
                        }
                    }
                } else {
                    // Update header
                    final String upd = """
                        UPDATE timesheets
                           SET end_date = ?, overtime_deci = ?, flextime_deci = ?
                         WHERE timesheet_id = ?
                    """;
                    try (PreparedStatement ps = c.prepareStatement(upd)) {
                        ps.setDate(1, java.sql.Date.valueOf(ts.getEndDate()));
                        ps.setInt(2, 0); // no getters available on your model
                        ps.setInt(3, 0);
                        ps.setLong(4, existingId);
                        ps.executeUpdate();
                    }

                    // Clear rows to resync
                    try (PreparedStatement del = c.prepareStatement("DELETE FROM timesheet_rows WHERE timesheet_id = ?")) {
                        del.setLong(1, existingId);
                        del.executeUpdate();
                    }
                }

                // Re-insert rows in order
                int lineNo = 1;
                for (TimesheetRow r : ts.getDetails()) {
                    insertRow(c, ts, lineNo++, r);
                }

                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("save(Timesheet) failed", ex);
        }
    }

    /** Overload expected by TimesheetEditBean; ensures UPDATE when id is known. */
    public void save(final Timesheet ts, final Long timesheetId) {
        if (ts == null) return;
        if (timesheetId != null) {
            timesheetIds.put(ts, timesheetId);
        }
        save(ts);
    }

    /** Load a single timesheet by DB id (used by TimesheetEditBean). */
    public Timesheet loadById(final Long timesheetId) {
        if (timesheetId == null) return null;
        final String sql = """
            SELECT t.timesheet_id, t.employee_id, t.end_date, t.overtime_deci, t.flextime_deci
            FROM timesheets t
            WHERE t.timesheet_id = ?
            LIMIT 1
        """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, timesheetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Timesheet ts = materializeTimesheet(rs);
                loadRows(c, ts);
                return ts;
            }
        } catch (SQLException ex) {
            throw new RuntimeException("loadById failed for id=" + timesheetId, ex);
        }
    }

    public Timesheet getMyNewest() {
        Employee me = currentUser.getEmployee();
		if (me == null) return null;
		final String sql = """
			SELECT t.timesheet_id, t.employee_id, t.end_date, t.overtime_deci, t.flextime_deci
			FROM timesheets t
			WHERE t.employee_id = ?
			ORDER BY t.created_at DESC, t.timesheet_id DESC
			LIMIT 1
		""";
		try (Connection c = ds.getConnection();
			PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setLong(1, requireEmployeeId(c, me));
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) return null;
				Timesheet ts = materializeTimesheet(rs, me);
				loadRows(c, ts);
				return ts;
			}
		} catch (SQLException ex) {
			throw new RuntimeException("getMyNewest() failed", ex);
		}
    }

    // ---------------- Helpers ----------------

    private Timesheet materializeTimesheet(ResultSet rs) throws SQLException {
        long empId = rs.getLong("employee_id");
        LocalDate end = rs.getDate("end_date").toLocalDate();
        Employee e = loadEmployeeById(empId);
        Timesheet ts = new Timesheet(e, end);
        ts.setOvertime(rs.getInt("overtime_deci"));
        ts.setFlextime(rs.getInt("flextime_deci"));
        timesheetIds.put(ts, rs.getLong("timesheet_id"));
        return ts;
    }

    private Timesheet materializeTimesheet(ResultSet rs, Employee knownEmployee) throws SQLException {
        LocalDate end = rs.getDate("end_date").toLocalDate();
        Timesheet ts = new Timesheet(knownEmployee, end);
        ts.setOvertime(rs.getInt("overtime_deci"));
        ts.setFlextime(rs.getInt("flextime_deci"));
        timesheetIds.put(ts, rs.getLong("timesheet_id"));
        return ts;
    }

    private void loadRows(Connection c, Timesheet ts) throws SQLException {
        Long tsId = timesheetIds.get(ts);
        if (tsId == null) return;

        final String sql = """
            SELECT row_id, line_no, project_id, work_package_id, packed_hours, notes
            FROM timesheet_rows
            WHERE timesheet_id = ?
            ORDER BY line_no ASC
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tsId);
            try (ResultSet rs = ps.executeQuery()) {
                ts.getDetails().clear();
                while (rs.next()) {
                    TimesheetRow r = new TimesheetRow();
                    r.setProjectId(rs.getInt("project_id"));
                    r.setWorkPackageId(rs.getString("work_package_id"));
                    r.setHours(unpackHours(rs.getLong("packed_hours")));
                    ts.getDetails().add(r);
                    rowIds.put(r, rs.getLong("row_id"));
                }
            }
        }
    }

    private void insertRow(Connection c, Timesheet ts, int lineNo, TimesheetRow r) throws SQLException {
        Long tsId = timesheetIds.get(ts);
        if (tsId == null) throw new IllegalStateException("Timesheet id unknown during row insert");

        final String ins = """
            INSERT INTO timesheet_rows (timesheet_id, line_no, project_id, work_package_id, packed_hours, notes)
            VALUES (?, ?, ?, ?, ?, NULL)
        """;
        try (PreparedStatement ps = c.prepareStatement(ins, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, tsId);
            ps.setInt(2, lineNo);
            ps.setInt(3, r.getProjectId());
            ps.setString(4, nvl(r.getWorkPackageId()));
            ps.setLong(5, packHours(safeHours(r)));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    rowIds.put(r, keys.getLong(1));
                }
            }
        }
    }

    private long requireEmployeeId(Connection c, Employee e) throws SQLException {
        final String find = "SELECT employee_id FROM employees WHERE emp_number = ?";
        try (PreparedStatement ps = c.prepareStatement(find)) {
            ps.setInt(1, e.getEmpNumber());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        final String ins = "INSERT INTO employees (name, emp_number, user_name, role) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(ins, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, nvl(e.getName(), "User " + e.getEmpNumber()));
            ps.setInt(2, e.getEmpNumber());
            ps.setString(3, nvl(e.getUserName(), "user" + e.getEmpNumber()));
            ps.setString(4, "USER");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private Employee loadEmployeeById(long employeeId) {
        final String sql = "SELECT name, emp_number, user_name, role FROM employees WHERE employee_id = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, employeeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Employee e = "ADMIN".equals(rs.getString("role")) ? new Admin() : new Employee();
                    e.setName(rs.getString("name"));
                    e.setEmpNumber(rs.getInt("emp_number"));
                    e.setUserName(rs.getString("user_name"));
                    return e;
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException("loadEmployeeById failed", ex);
        }
        Employee e = new Employee();
        e.setName("Unknown");
        e.setEmpNumber(-1);
        e.setUserName("unknown");
        return e;
    }

    private void ensureAdminExists() {
        final String countSql = "SELECT COUNT(*) FROM employees";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(countSql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            if (rs.getLong(1) == 0) {
                final String ins = "INSERT INTO employees (name, emp_number, user_name, role) VALUES ('System Admin', 0, 'admin', 'ADMIN')";
                try (PreparedStatement insPs = c.prepareStatement(ins)) {
                    insPs.executeUpdate();
                }
            }
        } catch (SQLException ignored) {
            // If schema not ready yet, skip seeding.
        }
    }

    private static LocalDate endOfWeekFriday(LocalDate ref) {
        return ref.with(DayOfWeek.FRIDAY);
    }

    private static float[] safeHours(TimesheetRow r) {
        float[] h = r.getHours();
        if (h == null || h.length != 7) return new float[]{0,0,0,0,0,0,0};
        return h;
    }

    private static String nvl(String s) { return s == null ? "" : s; }
    private static String nvl(String s, String def) { return s == null ? def : s; }

    private static long packHours(float[] hours) {
        long v = 0L;
        for (int i = 0; i < 7; i++) {
            int tenths = Math.round(hours[i] * 10f);
            if (tenths < 0) tenths = 0;
            if (tenths > 255) tenths = 255; // cap to 1 byte
            v |= ((long) tenths & 0xFFL) << (i * 8);
        }
        return v;
    }

    private static float[] unpackHours(long packed) {
        float[] out = new float[7];
        for (int i = 0; i < 7; i++) {
            int tenths = (int) ((packed >> (i * 8)) & 0xFFL);
            out[i] = tenths / 10f;
        }
        return out;
    }
}