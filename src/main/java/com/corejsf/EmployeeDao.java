package com.corejsf;


import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;

import javax.sql.DataSource;

import ca.bcit.infosys.employee.Credentials;
import ca.bcit.infosys.employee.Employee;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Connection;


@ApplicationScoped
public class EmployeeDao implements EmployeeList {
    
    @Resource(lookup = "java:/jdbc/timesheetsDS")
    private DataSource ds;
	
	@Inject
    private CredentialsDao credentialsDao;
    
    private static Employee mapEmployee(ResultSet rs) throws SQLException {
        String role = rs.getString("role");
        Employee e = "ADMIN".equalsIgnoreCase(role) ? new Admin() : new User();
        e.setName(rs.getString("name"));
        e.setEmpNumber(rs.getInt("empNumber"));
        e.setUserName(rs.getString("userName"));
        return e;
    }
    
    private static String roleOf(Employee e) {
        return (e instanceof Admin) ? "ADMIN" : "USER";
    }
    
    //queries
    
    private static final String SQL_FIND_ALL = """
            SELECT name, emp_number, user_name, role
            FROM employees
            ORDER BY emp_number
            """;
    private static final String SQL_FIND_BY_USERNAME = """
            SELECT name, emp_number, user_name, role
            FROM employees
            WHERE LOWER(user_name) = LOWER(?)
            """;
    private static final String SQL_FIND_BY_EMPNUM = """
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
        private static final String SQL_SELECT_EMP_ID_BY_USERNAME = """
            SELECT employee_id
            FROM employees
            WHERE LOWER(user_name) = LOWER(?)
            """;
        private static final String SQL_INSERT_CRED = """
            INSERT INTO credentials (employee_id, password_hash)
            VALUES (?, ?)
            """;
        private static final String SQL_UPDATE_EMP = """
            UPDATE employees
            SET name = ?, emp_number = ?, role = ?
            WHERE LOWER(user_name) = LOWER(?)
            """;
        private static final String SQL_DELETE_EMP_BY_USERNAME = """
            DELETE FROM employees
            WHERE LOWER(user_name) = LOWER(?)
            """;
        private static final String SQL_VERIFY = """
            SELECT 1
            FROM credentials c
            JOIN employees e ON e.employee_id = c.employee_id
            WHERE LOWER(e.user_name) = LOWER(?) AND c.password_hash = ?
            """;
        private static final String SQL_CHANGE_PASSWORD = """
            UPDATE credentials c
            JOIN employees e ON e.employee_id = c.employee_id
            SET c.password_hash = ?
            WHERE LOWER(e.user_name) = LOWER(?)
            """;
        
        @Override
		public List<Employee> getEmployees() {
			List<Employee> list = new ArrayList<>();
			try (Connection c = ds.getConnection();
				PreparedStatement ps = c.prepareStatement(SQL_SELECT_ALL);
				ResultSet rs = ps.executeQuery()) {
	
				while (rs.next()) {
					list.add(mapEmployee(rs));
				}
			} catch (SQLException e) {
			}
			return list;
		}
        
        @Override
		public Employee getEmployee(final String name) {
			try (Connection c = ds.getConnection();
				PreparedStatement ps = c.prepareStatement(SQL_SELECT_BY_USERNAME)) {
				ps.setString(1, name);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						return mapEmployee(rs);
					}
				}
			} catch (SQLException e) {
			}
			return null;
		}
        
        public Employee findByEmpNumber(int empNumber) throws SQLException{
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_EMPNUM)){
                ps.setInt(1, empNumber);
                try(ResultSet rs = ps.executeQuery()){
                    if(rs.next()) {
                        return mapEmployee(rs);
                    }
                    return null;
                }
            }
        }
        
        public int nextEmpNumber() throws SQLException{
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_NEXT_EMPNUM);
                    ResultSet rs = ps.executeQuery()){
                rs.next();
                return rs.getInt(1);
                
            }
        }
        
        @Override
		public void addEmployee(final Employee emp) {
			int number = emp.getEmpNumber();
			if (number == 0) {
				number = nextEmpNumber();
				emp.setEmpNumber(number);
			}
	
			final String role = (emp instanceof Admin) ? "ADMIN" : "USER";
	
			try (Connection c = ds.getConnection();
				PreparedStatement ps = c.prepareStatement(SQL_INSERT_EMP)) {
	
				ps.setString(1, emp.getName());
				ps.setInt(2, number);
				ps.setString(3, emp.getUserName());
				ps.setString(4, role);
				ps.executeUpdate();
	
				
				if (credentialsDao != null) {
					try {
						credentialsDao.createOrReset(emp.getUserName(), "password");
					} catch (Exception ignored) {

					}
				}
	
			} catch (SQLIntegrityConstraintViolationException dup) {

				if (dup.getMessage() != null && dup.getMessage().toLowerCase().contains("user_name")) {
					throw new IllegalStateException("Username already exists: " + emp.getUserName());
				} else {
					throw new IllegalStateException("Employee number already exists: " + number);
				}
			} catch (SQLException e) {
				throw new IllegalStateException("Unable to add employee", e);
			}
		}
        
        public void update(Employee e) throws SQLException{
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_UPDATE_EMP)){
                ps.setString(1, e.getName());
                ps.setInt(2, e.getEmpNumber());
                ps.setString(3, roleOf(e));
                ps.setString(4, e.getUserName());
                ps.executeUpdate();
            }
        }
        
        @Override
		public void deleteEmployee(final Employee emp) {
			if (emp != null && "admin".equalsIgnoreCase(emp.getUserName())) {
				return;
			}
			try (Connection c = ds.getConnection();
				PreparedStatement ps = c.prepareStatement(SQL_DELETE_BY_EMPNUM)) {
				ps.setInt(1, emp.getEmpNumber());
				ps.executeUpdate();	
			} catch (SQLException e) {

			}
		}
        
        public boolean verify(Credentials cred) throws SQLException {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(SQL_VERIFY)) {
                ps.setString(1, cred.getUserName());
                ps.setString(2, cred.getPassword());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }

        public void changePassword(String userName, String newPassword) throws SQLException {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(SQL_CHANGE_PASSWORD)) {
                ps.setString(1, newPassword);
                ps.setString(2, userName);
                ps.executeUpdate();
            }
        }
    
    


    
    
    

}
