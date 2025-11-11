package com.corejsf;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;
import javax.sql.DataSource;
import java.sql.*;
import javax.sql.DataSource;


/**
* Repository for managing all Employee and Credential operations.
* <p>
* Responsibilities:
* <ul>
* <li>Load employees from the database</li>
* <li>Create new employees and credentials</li>
* <li>Delete employees</li>
* <li>Authenticate user credentials</li>
* <li>Manage password updates</li>
* </ul>
*
* <p>This class interacts with two DB tables:
* <ul>
* <li><b>employees</b> — name, emp_number, user_name, role</li>
* <li><b>credentials</b> — employee_id, password_hash</li>
* </ul>
*/
@Named("employeeRepo")
@ApplicationScoped
public class EmployeeRepo implements EmployeeList{
    
    /** Injected datasource for DB access (WildFly + OKD). */
    @Resource(lookup = "java:jboss/datasources/timesheetsDS")
    private DataSource ds;

    /** Provides access to the logged-in user. */
    @Inject
    private CurrentUser currentUser;
	
    /**
    * Loads all employees ordered by their employee number.
    */
	@Override
    public List<Employee> getEmployees() {
        String sql = """
            SELECT employee_id, name, emp_number, user_name, role
            FROM employees ORDER BY emp_number
        """;
        List<Employee> list = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapEmployee(rs));
        } catch (SQLException e) {
            throw new RuntimeException("getEmployees failed", e);
        }
        return list;
    }
	
	/**
	* Load a single employee from database by username.
	* Case-insensitive lookup.
	*/
	@Override
    public Employee getEmployee(String userName) {
        String sql = """
            SELECT employee_id, name, emp_number, user_name, role
            FROM employees WHERE LOWER(user_name)=LOWER(?)
        """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapEmployee(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("getEmployee failed for " + userName, e);
        }
        return null;
    }
	
	/**
	* Creates a new employee and inserts default credentials.
	* Validates that username and emp_number are unique.
	*/
	@Override
    public void addEmployee(Employee emp) {
        // Enforce unique username & emp_number (if provided)
        if (userExists(emp.getUserName())) {
            throw new IllegalStateException("Username already exists: " + emp.getUserName());
        }
        if (emp.getEmpNumber() != 0 && empNumberExists(emp.getEmpNumber())) {
            throw new IllegalStateException("Employee number already exists: " + emp.getEmpNumber());
        }

        int empNumber = (emp.getEmpNumber() == 0) ? nextEmpNumber() : emp.getEmpNumber();
        String role = (emp instanceof Admin) ? "ADMIN" : "USER";

        String insertEmp = """
            INSERT INTO employees (name, emp_number, user_name, role)
            VALUES (?, ?, ?, ?)
        """;
        String insertCred = """
            INSERT INTO credentials (employee_id, password_hash)
            VALUES (?, ?)
        """;

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            long newEmployeeId;

            try (PreparedStatement ps = c.prepareStatement(insertEmp, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, emp.getName());
                ps.setInt(2, empNumber);
                ps.setString(3, emp.getUserName());
                ps.setString(4, role);
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("No employee_id generated.");
                    newEmployeeId = keys.getLong(1);
                }
            }

            // Default password policy: mirror the old repo’s “password”.
            try (PreparedStatement ps2 = c.prepareStatement(insertCred)) {
                ps2.setLong(1, newEmployeeId);
                ps2.setString(2, "password");
                ps2.executeUpdate();
            }

            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException("addEmployee failed for " + emp.getUserName(), e);
        }
    }

	/**
	* Deletes an employee using their emp_number.
	* Protects the admin account from deletion.
	*/
	@Override
    public void deleteEmployee(Employee emp) {
        if (emp == null) return;
        if ("admin".equalsIgnoreCase(emp.getUserName())) {
            // Do not delete the bootstrap admin, matching old behavior.
            return;
        }
        String sql = "DELETE FROM employees WHERE emp_number = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, emp.getEmpNumber());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("deleteEmployee failed for " + emp.getUserName(), e);
        }
    }
	
	/**
	* Load all username/password pairs used for authentication.
	* Mainly used by login logic.
	*/
	public Map<String, String> getLoginCombos() {
        String sql = """
            SELECT e.user_name, c.password_hash
            FROM credentials c
            JOIN employees e ON e.employee_id = c.employee_id
        """;
        Map<String, String> map = new HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getLoginCombos failed", e);
        }
        return map;
    }

	/**
	* Validate username/password against stored credentials.
	*/
    public boolean verifyUser(Credentials credential) {
        if (credential == null || credential.getUserName() == null) return false;
        String sql = """
            SELECT c.password_hash
            FROM credentials c
            JOIN employees e ON e.employee_id = c.employee_id
            WHERE LOWER(e.user_name) = LOWER(?)
        """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, credential.getUserName());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String stored = rs.getString(1);
                return stored != null && stored.equals(credential.getPassword());
            }
        } catch (SQLException e) {
            throw new RuntimeException("verifyUser failed for " + credential.getUserName(), e);
        }
    }

    /**
    * Update a user's password.
    * Also stamps last_changed timestamp.
    */
    public void changePassword(String userName, String newPassword) {
        String sql = """
            UPDATE credentials c
            JOIN employees e ON e.employee_id = c.employee_id
            SET c.password_hash = ?, c.last_changed = CURRENT_TIMESTAMP
            WHERE LOWER(e.user_name) = LOWER(?)
        """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newPassword);
            ps.setString(2, userName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("changePassword failed for " + userName, e);
        }
    }

    /** Change password for the currently logged-in user. */
    public void changeMyPassword(String newPassword) {
        if (currentUser == null || currentUser.getEmployee() == null)
            throw new IllegalStateException("No current user.");
        changePassword(currentUser.getEmployee().getUserName(), newPassword);
    }

    /**
     * Returns the currently logged-in employee, or null if no session exists.
     */
    public Employee getCurrentEmployee() {
        return (currentUser == null) ? null : currentUser.getEmployee();
    }

    /** Return the first ADMIN user found. */
    public Employee getAdministrator() {
        String sql = """
            SELECT employee_id, name, emp_number, user_name, role
            FROM employees
            WHERE role='ADMIN'
            ORDER BY employee_id
            LIMIT 1
        """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return mapEmployee(rs);
        } catch (SQLException e) {
            throw new RuntimeException("getAdministrator failed", e);
        }
        return null;
    }

    /**
    * Logs out a user and returns navigation page name.
    */
    public String logout(Employee employee) {
        if (employee == null) return "login";
        if (currentUser != null
                && currentUser.getEmployee() != null
                && employee.getUserName() != null
                && employee.getUserName().equalsIgnoreCase(currentUser.getEmployee().getUserName())) {
            currentUser = null;
        }
        return "login";
    }

    /**
    * Compute next available employee number (MAX+1).
    */
    public int nextEmpNumber() {
        String sql = "SELECT COALESCE(MAX(emp_number), 0) + 1 FROM employees";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("nextEmpNumber failed", e);
        }
    }

    // ---------- Private helpers ----------

    /** Check if username already exists. */
    private boolean userExists(String userName) {
        String sql = "SELECT 1 FROM employees WHERE LOWER(user_name)=LOWER(?)";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userName);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw new RuntimeException("userExists failed", e);
        }
    }

    /** Check if an employee number is already used. */
    private boolean empNumberExists(int empNumber) {
        String sql = "SELECT 1 FROM employees WHERE emp_number=?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, empNumber);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw new RuntimeException("empNumberExists failed", e);
        }
    }

    /**
    * Maps a ResultSet row into an Employee or Admin instance.
    */
    private Employee mapEmployee(ResultSet rs) throws SQLException {
        String role = rs.getString("role");
        Employee e = "ADMIN".equalsIgnoreCase(role) ? new Admin() : new User();
        e.setName(rs.getString("name"));
        e.setEmpNumber(rs.getInt("emp_number"));
        e.setUserName(rs.getString("user_name"));
        return e;
    }
}