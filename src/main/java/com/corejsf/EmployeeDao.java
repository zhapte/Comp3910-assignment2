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
public class EmployeeDao {
    
    @Resource(lookup = "java:/jdbc/timesheetsDS")
    private DataSource ds;
    
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
        
        public List<Employee> findAll() throws SQLException{
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_FIND_ALL);
                    ResultSet rs = ps.executeQuery()){
                        List<Employee> out = new ArrayList<>();
                        while(rs.next()) {
                            out.add(mapEmployee(rs));
                        }
                        return out;
                    }
        }
        
        public Employee findByUserName(String userName) throws SQLException {
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_USERNAME)){
                ps.setString(1, userName);
                try(ResultSet rs = ps.executeQuery()){
                    if(rs.next()) {
                        return mapEmployee(rs);
                    }
                    return null;
                }
            }
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
        
        public long create(Employee e, String rawPassword) throws SQLException{
            try(Connection c = ds.getConnection()){
                c.setAutoCommit(false);
                long employeeId;
                
                try(PreparedStatement ps = c.prepareStatement(SQL_INSERT_EMP, Statement.RETURN_GENERATED_KEYS)){
                    ps.setString(1, e.getName());
                    ps.setInt(2, e.getEmpNumber());
                    ps.setString(3, e.getUserName());
                    ps.executeUpdate();
                    try(ResultSet keys = ps.getGeneratedKeys()){
                        if(!keys.next()) {
                            c.rollback();
                            throw new SQLException("No employee_id generated.");
                        }
                        employeeId = keys.getLong(1);
                    }
                }
                try(PreparedStatement ps = c.prepareStatement(SQL_INSERT_CRED)){
                    ps.setLong(1, employeeId);
                    ps.setString(2, rawPassword);
                    ps.executeUpdate();
                }
                c.commit();
                return employeeId;
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
        
        public void deleteByUserName(String userName) throws SQLException{
            if("admin".equalsIgnoreCase(userName)) return;
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_DELETE_EMP_BY_USERNAME)){
                ps.setString(1, userName);
                ps.executeUpdate();
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
