package com.corejsf;

import javax.sql.DataSource;

import ca.bcit.infosys.employee.Credentials;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CredentialsDao {
    @Resource(lookup = "java:/jdbc/timesheetsDS")
    private DataSource ds;
    
    private static final String SQL_VERIFY = """
            SELECT 1
            FROM credentials c
            JOIN employees e ON e.employees_id = c.employee_id
            WHERE LOWER(e.user_name) = LOWER(?) AND c.password_hash = ?
            """;
    
    private static final String SQL_CHANGE_PASSWORD = """
            UPDATE credentials c
            JOIN employees e ON e.employee_id = c.employee_id
            SET c.password_hash = ?
            WHERE LOWER(e.user_name) = LOWER(?)
            """;
    
    public boolean verify(Credentials cred) throws SQLException{
        try(Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_VERIFY)){
            ps.setString(1, cred.getUserName());
            ps.setString(2, cred.getPassword());
            try(ResultSet rs = ps.executeQuery()){
                return rs.next();
            }
        }
        
    }
    
    public void changePassword(String userName, String newPassword) throws SQLException{
        try(Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_CHANGE_PASSWORD)){
            ps.setString(1, newPassword);
            ps.setString(2, userName);
            ps.executeUpdate();
        }
    }
    

}
