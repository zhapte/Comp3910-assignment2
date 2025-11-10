package com.corejsf;


import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;

import javax.sql.DataSource;

import ca.bcit.infosys.employee.*;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Connection;

import jakarta.inject.Inject;
import java.util.Map;
import java.util.HashMap;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;


@ApplicationScoped
public class EmployeeDao implements EmployeeList {

    @Resource(lookup = "java:jboss/datasources/timesheetsDS")
    private DataSource ds;

    @Inject
    private CredentialsDao credentialsDao;

    @Inject
    private CurrentUser currentUser;

    // ---------- SQL ----------
    private static final String SQL_SELECT_ALL = """
        SELECT name, emp_number, user_name, role
        FROM employees
        ORDER BY emp_number
        """;

    private static final String SQL_SELECT_BY_USERNAME = """
        SELECT name, emp_number, user_name, role
        FROM employees
        WHERE LOWER(user_name) = LOWER(?)
        """;

    private static final String SQL_SELECT_BY_EMPNUM = """
        SELECT name, emp_number, user_name, role
        FROM employees
        WHERE emp_number = ?
        """;

    private static final String SQL_NEXT_EMPNUM = """
        SELECT COALESCE(MAX(emp_number), 0) + 1 AS next_num
        FROM employees
        """;

    private static final String SQL_INSERT_EMP = """
        INSERT INTO employees (name, emp_number, user_name, role)
        VALUES (?, ?, ?, ?)
        """;

    private static final String SQL_DELETE_BY_EMPNUM = """
        DELETE FROM employees
        WHERE emp_number = ?
        """;

    private static final String SQL_UPDATE_EMP = """
        UPDATE employees
        SET name = ?, emp_number = ?, role = ?
        WHERE LOWER(user_name) = LOWER(?)
        """;

    private static final String SQL_VERIFY = """
        SELECT 1
        FROM credentials c
        JOIN employees e ON e.employee_id = c.employee_id
        WHERE LOWER(e.user_name) = LOWER(?) AND c.password_hash = ?
        """;

    private static final String SQL_LOGIN_COMBOS = """
        SELECT e.user_name, c.password_hash
        FROM employees e
        JOIN credentials c ON c.employee_id = e.employee_id
        """;

    private static final String SQL_CHANGE_PASSWORD = """
        UPDATE credentials c
        JOIN employees e ON e.employee_id = c.employee_id
        SET c.password_hash = ?
        WHERE LOWER(e.user_name) = LOWER(?)
        """;

    private static final String SQL_SELECT_ADMIN = """
        SELECT name, emp_number, user_name, role
        FROM employees
        WHERE role = 'ADMIN'
        ORDER BY emp_number
        LIMIT 1
        """;

    // ---------- Helpers ----------
    private static Employee mapEmployee(ResultSet rs) throws SQLException {
        String role = rs.getString("role");
        Employee e = "ADMIN".equalsIgnoreCase(role) ? new Admin() : new User();
        e.setName(rs.getString("name"));
        e.setEmpNumber(rs.getInt("emp_number"));  
        e.setUserName(rs.getString("user_name"));  
        return e;
    }

    private static String roleOf(Employee e) {
        return (e instanceof Admin) ? "ADMIN" : "USER";
    }

    public Employee findByEmpNumber(int empNumber) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_SELECT_BY_EMPNUM)) {
            ps.setInt(1, empNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapEmployee(rs);
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public int nextEmpNumber() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_NEXT_EMPNUM);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 1;
    }

    // ---------- EmployeeList ----------
    @Override
    public List<Employee> getEmployees() {
        List<Employee> list = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapEmployee(rs));
        } catch (SQLException ignored) {}
        return list;
    }

    @Override
    public Employee getEmployee(final String name) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_SELECT_BY_USERNAME)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapEmployee(rs);
            }
        } catch (SQLException ignored) {}
        return null;
    }

    @Override
    public Map<String, String> getLoginCombos() {
        Map<String, String> map = new HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_LOGIN_COMBOS);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String u = rs.getString(1);
                String p = rs.getString(2);
                if (u != null && p != null) map.put(u, p);
            }
        } catch (SQLException ignored) {}
        return map;
    }

    @Override
    public Employee getCurrentEmployee() {
        return currentUser != null ? currentUser.getEmployee() : null;
    }

    @Override
    public Employee getAdministrator() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_SELECT_ADMIN);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return mapEmployee(rs);
        } catch (SQLException ignored) {}
        return null;
    }

    @Override
    public boolean verifyUser(Credentials credential) {
        if (credential == null) return false;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_VERIFY)) {
            ps.setString(1, credential.getUserName());
            ps.setString(2, credential.getPassword());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ignored) {}
        return false;
    }

    @Override
    public String logout(Employee employee) {
        if (employee == null) return "login";
        if (currentUser != null && currentUser.getEmployee() != null) {
            if (employee.getUserName() != null &&
                employee.getUserName().equalsIgnoreCase(currentUser.getEmployee().getUserName())) {
                currentUser.setEmployee(null);
                currentUser.clearSelectedTimesheet();
            }
        }
        return "login";
    }

    @Override
    public void deleteEmployee(final Employee emp) {
        if (emp == null) return;
        if ("admin".equalsIgnoreCase(emp.getUserName())) return;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_DELETE_BY_EMPNUM)) {
            ps.setInt(1, emp.getEmpNumber());
            ps.executeUpdate(); 
        } catch (SQLException ignored) {}
    }

    @Override
    public void addEmployee(final Employee emp) {
        if (emp == null) return;

        int number = emp.getEmpNumber();
        if (number == 0) {
            number = nextEmpNumber();
            emp.setEmpNumber(number);
        }
        final String role = roleOf(emp);

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_INSERT_EMP)) {
            ps.setString(1, emp.getName());
            ps.setInt(2, number);
            ps.setString(3, emp.getUserName());
            ps.setString(4, role);
            ps.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException dup) {
            String msg = dup.getMessage() == null ? "" : dup.getMessage().toLowerCase();
            if (msg.contains("user_name")) {
                throw new IllegalStateException("Username already exists: " + emp.getUserName());
            } else {
                throw new IllegalStateException("Employee number already exists: " + number);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to add employee", e);
        }

        try {
            if (credentialsDao != null) {
                credentialsDao.changePassword(emp.getUserName(), "password");
            }
        } catch (Exception ignored) {}
    }


    public void update(Employee e) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_UPDATE_EMP)) {
            ps.setString(1, e.getName());
            ps.setInt(2, e.getEmpNumber());
            ps.setString(3, roleOf(e));
            ps.setString(4, e.getUserName());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }


    public void changePassword(String userName, String newPassword) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_CHANGE_PASSWORD)) {
            ps.setString(1, newPassword);
            ps.setString(2, userName);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }
}