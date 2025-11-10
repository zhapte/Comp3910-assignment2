package com.corejsf;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.Resource;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;

import java.io.Serializable;
import java.util.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;


@Named("timeSheetRepo")
@ApplicationScoped
public class TimeSheetRepo  implements TimesheetCollection, Serializable {
	
	@Resource(lookup = "java:jboss/datasources/timesheetsDS")
    private DataSource ds;
	
	@Inject
    private CurrentUser currentUser;

	    @Override
    public List<Timesheet> getTimesheets() {
        String sql = """
            SELECT ts.timesheet_id, ts.employee_id, ts.end_date, ts.overtime_deci, ts.flextime_deci,
                   e.name, e.emp_number, e.user_name, e.role
            FROM timesheets ts
            JOIN employees e ON e.employee_id = ts.employee_id
            ORDER BY ts.employee_id, ts.end_date DESC
        """;

        List<Timesheet> out = new ArrayList<>();
        Map<Long, Timesheet> byId = new LinkedHashMap<>();

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                long tsId = rs.getLong("timesheet_id");
                byId.put(tsId, mapTimesheetHeader(rs));
            }

            for (Map.Entry<Long, Timesheet> e : byId.entrySet()) {
                loadRows(e.getKey(), e.getValue());
                out.add(e.getValue());
            }
        } catch (SQLException ex) {
            throw new RuntimeException("getTimesheets failed", ex);
        }
        return out;
    }

    @Override
    public List<Timesheet> getTimesheets(Employee e) {
        if (e == null) return Collections.emptyList();

        String sql = """
            SELECT ts.timesheet_id, ts.employee_id, ts.end_date, ts.overtime_deci, ts.flextime_deci,
                   emp.name, emp.emp_number, emp.user_name, emp.role
            FROM timesheets ts
            JOIN employees emp ON emp.employee_id = ts.employee_id
            WHERE ts.employee_id = ?
            ORDER BY ts.end_date DESC
        """;

        List<Timesheet> out = new ArrayList<>();
        Map<Long, Timesheet> byId = new LinkedHashMap<>();

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setLong(1, employeeIdFor(e, c));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long tsId = rs.getLong("timesheet_id");
                    byId.put(tsId, mapTimesheetHeader(rs));
                }
            }

            for (Map.Entry<Long, Timesheet> en : byId.entrySet()) {
                loadRows(en.getKey(), en.getValue(), c);
                out.add(en.getValue());
            }
        } catch (SQLException ex) {
            throw new RuntimeException("getTimesheets(Employee) failed", ex);
        }
        return out;
    }

    @Override
    public Timesheet getCurrentTimesheet(Employee e) {
        List<Timesheet> list = getTimesheets(e);
        if (list.isEmpty()) return null;

        LocalDate today = LocalDate.now();

        Comparator<Timesheet> byAbsDays =
                Comparator.comparingLong(ts -> Math.abs(ChronoUnit.DAYS.between(ts.getEndDate(), today)));
        Comparator<Timesheet> tieBreaker =
                Comparator.comparing(ts -> ts.getEndDate().isBefore(today));

        return list.stream()
                .filter(ts -> ts.getEndDate() != null)
                .min(byAbsDays.thenComparing(tieBreaker))
                .orElse(list.getFirst());
    }

    @Override
    public String addTimesheet() {
        Employee me = currentUser.getEmployee();
        if (me == null) throw new IllegalStateException("No current user.");
        LocalDate end = LocalDate.now().with(java.time.DayOfWeek.FRIDAY);
        Timesheet ts = new Timesheet(me, end);
        for (int i = 0; i < 5; i++) ts.addRow(); // 5 empty rows by default
        save(ts);
        return "created";
    }

    // ---------- Convenience for current user ----------

    public Timesheet getMyCurrentTimesheet() {
        return getCurrentTimesheet(currentUser.getEmployee());
    }

    public List<Timesheet> getMyTimesheets() {
        return getTimesheets(currentUser.getEmployee());
    }

    public Timesheet getMyNewest() {
        List<Timesheet> mine = getMyTimesheets();
        if (mine.isEmpty()) throw new NoSuchElementException("No timesheets for current user.");
        return mine.get(0); // list is DESC by end_date
    }

    // ---------- Persistence (upsert header, replace rows) ----------

    public void save(Timesheet ts) {
        if (ts == null || ts.getEmployee() == null || ts.getEndDate() == null) return;

        String upsertTs = """
            INSERT INTO timesheets (employee_id, end_date, overtime_deci, flextime_deci)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              overtime_deci = VALUES(overtime_deci),
              flextime_deci = VALUES(flextime_deci)
        """;

        String deleteRows = """
            DELETE tr FROM timesheet_rows tr
            JOIN timesheets ts ON ts.timesheet_id = tr.timesheet_id
            WHERE ts.employee_id = ? AND ts.end_date = ?
        """;

        String insertRow = """
            INSERT INTO timesheet_rows (timesheet_id, line_no, project_id, work_package_id, packed_hours, notes)
            VALUES (?, ?, ?, ?, ?, ?)
        """;

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);

            long empId = employeeIdFor(ts.getEmployee(), c);

            // 1) Upsert header (overtime/flextime default to 0 to avoid missing getters)
            try (PreparedStatement ps = c.prepareStatement(upsertTs)) {
                ps.setLong(1, empId);
                ps.setDate(2, java.sql.Date.valueOf(ts.getEndDate()));
                ps.setInt(3, 0);
                ps.setInt(4, 0);
                ps.executeUpdate();
            }

            // 2) Resolve timesheet_id
            long tsId = findTimesheetId(empId, ts.getEndDate(), c);

            // 3) Replace rows for this header
            try (PreparedStatement psDel = c.prepareStatement(deleteRows)) {
                psDel.setLong(1, empId);
                psDel.setDate(2, java.sql.Date.valueOf(ts.getEndDate()));
                psDel.executeUpdate();
            }

            // 4) Insert detail rows
            try (PreparedStatement psIns = c.prepareStatement(insertRow)) {
                List<TimesheetRow> rows = ts.getDetails();
                for (int i = 0; i < rows.size(); i++) {
                    TimesheetRow r = rows.get(i);
                    psIns.setLong(1, tsId);
                    psIns.setInt(2, i + 1); // line_no
                    psIns.setInt(3, r.getProjectId());
                    psIns.setString(4, Optional.ofNullable(r.getWorkPackageId()).orElse(""));
                    psIns.setLong(5, packHours(r.getHours())); // 7-day deci-hours packed into BIGINT
                    psIns.setString(6, null); // notes, wire later if desired
                    psIns.addBatch();
                }
                psIns.executeBatch();
            }

            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("save(timesheet) failed", e);
        }
    }

    // ---------- Mapping & SQL helpers ----------

    private Timesheet mapTimesheetHeader(ResultSet rs) throws SQLException {
        String role = rs.getString("role");
        Employee e = "ADMIN".equalsIgnoreCase(role) ? new Admin() : new User();
        e.setName(rs.getString("name"));
        e.setEmpNumber(rs.getInt("emp_number"));
        e.setUserName(rs.getString("user_name"));

        LocalDate endDate = rs.getDate("end_date").toLocalDate();

        // If your Timesheet has a constructor (Employee, LocalDate) use it:
        Timesheet ts = new Timesheet(e, endDate);

        // If your model later provides setters for overtime/flextime, set them here:
        // ts.setOvertime(rs.getInt("overtime_deci"));
        // ts.setFlextime(rs.getInt("flextime_deci"));

        return ts;
    }

    private void loadRows(long timesheetId, Timesheet ts) {
        try (Connection c = ds.getConnection()) {
            loadRows(timesheetId, ts, c);
        } catch (SQLException e) {
            throw new RuntimeException("loadRows failed", e);
        }
    }

    private void loadRows(long timesheetId, Timesheet ts, Connection c) throws SQLException {
        String sql = """
            SELECT line_no, project_id, work_package_id, packed_hours, notes
            FROM timesheet_rows
            WHERE timesheet_id = ?
            ORDER BY line_no
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, timesheetId);
            try (ResultSet rs = ps.executeQuery()) {
                ts.getDetails().clear();
                while (rs.next()) {
                    TimesheetRow row = new TimesheetRow();
                    row.setProjectId(rs.getInt("project_id"));
                    row.setWorkPackageId(Optional.ofNullable(rs.getString("work_package_id")).orElse(""));
                    row.setHours(unpackHours(rs.getLong("packed_hours")));
                    ts.getDetails().add(row);
                }
            }
        }
    }

    private long employeeIdFor(Employee e, Connection c) throws SQLException {
        if (e.getEmpNumber() != 0) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT employee_id FROM employees WHERE emp_number=?")) {
                ps.setInt(1, e.getEmpNumber());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT employee_id FROM employees WHERE LOWER(user_name)=LOWER(?)")) {
            ps.setString(1, e.getUserName());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Employee not found: " + e.getUserName() + " (#" + e.getEmpNumber() + ")");
    }

    private long findTimesheetId(long employeeId, LocalDate end, Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT timesheet_id FROM timesheets WHERE employee_id=? AND end_date=?")) {
            ps.setLong(1, employeeId);
            ps.setDate(2, java.sql.Date.valueOf(end));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Timesheet header not found after upsert.");
    }

    // ---------- Hours packing: 7 bytes of deci-hours into a BIGINT ----------

    private static long packHours(float[] hours) {
        long v = 0L;
        int len = (hours == null) ? 0 : Math.min(hours.length, 7);
        for (int d = 0; d < 7; d++) {
            int deci = 0;
            if (d < len && hours[d] >= 0) {
                float clamped = Math.max(0f, Math.min(24f, hours[d]));
                deci = Math.round(clamped * 10f); // 0..240 fits in 1 byte
            }
            v |= ((long) (deci & 0xFF)) << (8L * d);
        }
        return v;
    }

    private static float[] unpackHours(long packed) {
        float[] h = new float[7];
        for (int d = 0; d < 7; d++) {
            int deci = (int) ((packed >> (8L * d)) & 0xFF);
            h[d] = deci / 10f;
        }
        return h;
    }

    // ---------- Admin helper ----------

    private Employee getAdministrator() throws SQLException {
        String sql = """
            SELECT name, emp_number, user_name
            FROM employees
            WHERE role='ADMIN'
            ORDER BY employee_id
            LIMIT 1
        """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            Employee e = new Admin();
            e.setName(rs.getString("name"));
            e.setEmpNumber(rs.getInt("emp_number"));
            e.setUserName(rs.getString("user_name"));
            return e;
        }
    }


   
	
}