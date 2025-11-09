package com.corejsf;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Date;
import java.sql.Statement;


import java.util.ArrayList;
import java.util.List;
import java.time.LocalDate;

@ApplicationScoped
public class TimesheetDao implements TimesheetCollection {

    @Resource(lookup = "java:/jdbc/timesheetsDS")   
    private DataSource ds;

    @Inject
    private CurrentUser currentUser;

    @Inject
    private TimesheetRowDao timesheetRowDao;

    // ---------- SQL ----------
    private static final String SQL_SELECT_ALL = """
        SELECT t.timesheet_id, t.employee_id, t.end_date, t.overtime_deci, t.flextime_deci, t.created_at,
               e.name, e.emp_number, e.user_name, e.role
        FROM timesheets t
        JOIN employees e ON e.employee_id = t.employee_id
        ORDER BY t.end_date DESC
        """;

    private static final String SQL_SELECT_BY_EMP_NUM = """
        SELECT t.timesheet_id, t.employee_id, t.end_date, t.overtime_deci, t.flextime_deci, t.created_at,
               e.name, e.emp_number, e.user_name, e.role
        FROM timesheets t
        JOIN employees e ON e.employee_id = t.employee_id
        WHERE e.emp_number = ?
        ORDER BY t.end_date DESC
        """;

    private static final String SQL_SELECT_CURRENT_BY_EMP_NUM = """
        SELECT t.timesheet_id, t.employee_id, t.end_date, t.overtime_deci, t.flextime_deci, t.created_at,
               e.name, e.emp_number, e.user_name, e.role
        FROM timesheets t
        JOIN employees e ON e.employee_id = t.employee_id
        WHERE e.emp_number = ?
        ORDER BY ABS(DATEDIFF(t.end_date, CURRENT_DATE())),
                 (t.end_date < CURRENT_DATE()) ASC
        LIMIT 1
        """;

    private static final String SQL_INSERT = """
        INSERT INTO timesheets (employee_id, end_date, overtime_deci, flextime_deci)
        VALUES (?, ?, 0, 0)
        """;

    private static final String SQL_EMPLOYEE_ID_BY_EMP_NUM = """
        SELECT employee_id
        FROM employees
        WHERE emp_number = ?
        """;

    // ---------- TimesheetCollection impl ----------

    @Override
    public List<Timesheet> getTimesheets() {
        final List<Timesheet> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                out.add(mapTimesheet(rs, /*loadRows*/ true));
            }
        } catch (SQLException ignored) { }
        return out;
    }

    @Override
    public List<Timesheet> getTimesheets(final Employee e) {
        if (e == null) return List.of();
        final List<Timesheet> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_SELECT_BY_EMP_NUM)) {
            ps.setInt(1, e.getEmpNumber());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapTimesheet(rs, true));
                }
            }
        } catch (SQLException ignored) { }
        return out;
    }

    @Override
    public Timesheet getCurrentTimesheet(final Employee e) {
        if (e == null) return null;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_SELECT_CURRENT_BY_EMP_NUM)) {
            ps.setInt(1, e.getEmpNumber());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapTimesheet(rs, true);
                }
            }
        } catch (SQLException ignored) { }
        return null;
    }

    @Override
    public String addTimesheet() {
       
        final Employee me = currentUser != null ? currentUser.getEmployee() : null;
        if (me == null) return "login";

        final LocalDate friday = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        final Long employeeId = findEmployeeIdByEmpNumber(me.getEmpNumber());
        if (employeeId == null) {
           
            return "created";
        }

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, employeeId);
            ps.setDate(2, Date.valueOf(friday));
            ps.executeUpdate();


        } catch (SQLException ignored) { }
        return "created";
    }

    // ---------- Helpers ----------

    private Long findEmployeeIdByEmpNumber(final int empNumber) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_EMPLOYEE_ID_BY_EMP_NUM)) {
            ps.setInt(1, empNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException ignored) { }
        return null;
    }

    private Timesheet mapTimesheet(final ResultSet rs, final boolean loadRows) throws SQLException {
        final String role = rs.getString("role");
        final Employee emp = "ADMIN".equalsIgnoreCase(role) ? new Admin() : new User();
        emp.setName(rs.getString("name"));
        emp.setEmpNumber(rs.getInt("emp_number"));
        emp.setUserName(rs.getString("user_name"));

        final LocalDate end = rs.getDate("end_date").toLocalDate();
        final Timesheet ts = new Timesheet(emp, end);
        ts.setOvertime(rs.getInt("overtime_deci")); 
        ts.setFlextime(rs.getInt("flextime_deci"));

        if (loadRows && timesheetRowDao != null) {
            final long tsId = rs.getLong("timesheet_id");
            List<TimesheetRow> rows = timesheetRowDao.findRowsForTimesheet(tsId); 
            if (rows != null) {
                ts.setDetails(rows);
            }
        }
        return ts;
    }
}