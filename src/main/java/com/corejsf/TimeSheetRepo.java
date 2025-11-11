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

/**
* Repository / DAO for {@link Timesheet} data.
* <p>
* Responsibilities:
* <ul>
* <li>Materialize {@code Timesheet} and {@code TimesheetRow} objects from the database</li>
* <li>Persist changes (insert/update) to the <code>timesheets</code> and <code>timesheet_rows</code> tables</li>
* <li>Provide convenience lookups for the current user</li>
* <li>Seed an admin employee record if the employee table is empty</li>
* </ul>
*
* <p><strong>Notes on model constraints</strong>:
* The provided model classes do not expose getters for overtime/flextime in hours.
* This implementation stores/reads the raw decihour values from the DB but sets zeros
* when saving (see comments in {@link #save(Timesheet)}). Adjust here when the model API changes.</p>
*/
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

    /**
    * Fetch all timesheets for all employees, newest end date last for each employee.
    *
    * @return list of fully populated {@link Timesheet}s (header + rows)
    * @throws RuntimeException on SQL errors
    */
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

    /**
    * Fetch all timesheets for a specific employee.
    *
    * @param e employee to filter by (nullable)
    * @return list of fully populated timesheets, newest end date first
    */
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

    /**
    * Load the current timesheet for an employee.
    * <p>
    * Strategy:
    * <ol>
    * <li>Try an exact match for <em>this week's Friday</em> (based on server clock),
    * picking the most recently created if duplicates exist.</li>
    * <li>Fallback to the sheet whose end_date is closest to today (ties: prefer future,
    * then later end_date).</li>
    * </ol>
    *
    * @param e target employee (nullable)
    * @return the best-matching current {@link Timesheet}, or {@code null}
    */
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

    /**
    * Create a new, empty timesheet for the current user covering this week (ending Friday).
    * Also seeds 5 empty rows so the UI has something to render.
    *
    * @return navigation/status string; "created" on success, or "no-user" if unauthenticated
    */
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

    /** @return the current user's best-matching current sheet (convenience). */
    public Timesheet getMyCurrentTimesheet() {
        return getCurrentTimesheet(currentUser.getEmployee());
    }

    /** @return all timesheets for the current user (convenience). */
    public List<Timesheet> getMyTimesheets() {
        return getTimesheets(currentUser.getEmployee());
    }

    /**
    * Persist the provided {@link Timesheet} and its rows.
    * <p>
    * If the timesheet has no known DB id, a header row is inserted and the generated
    * key is tracked in {@link #timesheetIds}. Otherwise the header is updated. Rows
    * are re-synchronized by deleting and re-inserting in line order.
    *
    * <p><strong>Overtime/Flextime:</strong> The model lacks getters for hours;
    * we currently set DB values to 0. If/when getters are added, wire them here.</p>
    *
    * @param ts timesheet to save (nullable is a no-op)
    */
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

    
    /**
    * Fetch the newest-created timesheet for the current user.
    *
    * @return most recently created {@link Timesheet}, or {@code null}
    */
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

    /**
    * Build a {@link Timesheet} from the current row of the {@link ResultSet}.
    * Also caches the <code>timesheet_id</code> mapping for later updates.
    */
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

    /** As above, but reuse a known {@link Employee} to avoid an extra DB lookup. */
    private Timesheet materializeTimesheet(ResultSet rs, Employee knownEmployee) throws SQLException {
        LocalDate end = rs.getDate("end_date").toLocalDate();
        Timesheet ts = new Timesheet(knownEmployee, end);
        ts.setOvertime(rs.getInt("overtime_deci"));
        ts.setFlextime(rs.getInt("flextime_deci"));
        timesheetIds.put(ts, rs.getLong("timesheet_id"));
        return ts;
    }

    
    /**
    * Load all rows for a given timesheet and attach them to its details list.
    */
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

    /**
    * Insert one detail row for a timesheet.
    *
    * @param c open connection (transactional)
    * @param ts parent timesheet (must already have an id)
    * @param lineNo 1-based order number
    * @param r row to persist
    */
    private void insertRow(Connection c, Timesheet ts, int lineNo, TimesheetRow r) throws SQLException {
        Long tsId = timesheetIds.get(ts);
		if (tsId == null) throw new IllegalStateException("Timesheet id unknown during row insert");
	
		final String ins = """
			INSERT INTO timesheet_rows (timesheet_id, line_no, project_id, work_package_id, packed_hours, notes)
			VALUES (?, ?, ?, ?, ?, ?)
		""";
		try (PreparedStatement ps = c.prepareStatement(ins, Statement.RETURN_GENERATED_KEYS)) {
			ps.setLong(1, tsId);
			ps.setInt(2, lineNo);
			ps.setInt(3, r.getProjectId());
			ps.setString(4, nvl(r.getWorkPackageId()));
			ps.setLong(5, packHours(safeHours(r)));  // SAT..FRI as in your bean
			ps.setString(6, r.getNotes());           // <-- persist notes now
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				if (keys.next()) {
					rowIds.put(r, keys.getLong(1));
				}
			}
		}
    }

    /**
    * Find an employee id by <code>emp_number</code>, inserting a new record if missing.
    *
    * @return the existing or newly generated <code>employee_id</code>
    */
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

    /**
    * Translate a DB employee id to a minimal {@link Employee} instance.
    * Creates a generic placeholder if the id cannot be resolved (should be rare).
    */
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

    /**
    * Ensure there is at least one admin/seed row in <code>employees</code>.
    * If the table is missing (schema not ready), failures are ignored.
    */
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

    /** @return the Friday of the week containing {@code ref}. */
    private static LocalDate endOfWeekFriday(LocalDate ref) {
        return ref.with(DayOfWeek.FRIDAY);
    }

    /** Defensive copy for hours array: must be length 7 (Sat..Fri). */
    private static float[] safeHours(TimesheetRow r) {
        float[] h = r.getHours();
        if (h == null || h.length != 7) return new float[]{0,0,0,0,0,0,0};
        return h;
    }

    /** Null-safe helpers. */
    private static String nvl(String s) { return s == null ? "" : s; }
    private static String nvl(String s, String def) { return s == null ? def : s; }

    
    /**
    * Pack seven day-hour values (in hours, fractional to 0.1h) into a 56-bit long.
    * Each day is stored as an unsigned byte of <em>tenths</em> of an hour.
    */
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

    /** Reverse of {@link #packHours(float[])}. */
    private static float[] unpackHours(long packed) {
        float[] out = new float[7];
        for (int i = 0; i < 7; i++) {
            int tenths = (int) ((packed >> (i * 8)) & 0xFFL);
            out[i] = tenths / 10f;
        }
        return out;
    }
}