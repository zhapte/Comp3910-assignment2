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
public class TimesheetDao {
    @Resource(lookup = "java:/jdbc/timesheetDS")
    private DataSource ds;
    
    public static final class TimesheetRecord{
        public final long timesheetId;
        public final long employeeId;
        public final LocalDate endDate;
        public final int overtimeDeci;
        public final int flextimeDeci;
        public final Timestamp createdAt;
        
        public TimesheetRecord(long tsId,
                long empId,
                LocalDate end,
                int otDeci,
                int flexDeci,
                Timestamp created) {
            this.timesheetId = tsId;
            this.employeeId = empId;
            this.endDate = end;
            this.overtimeDeci = otDeci;
            this.flextimeDeci = flexDeci;
            this.createdAt = created;
        }
    }
    
    //queries
    private static final String SQL_INSERT = """
            INSERT INTO timesheets (employee_id, end_date, overtime_deci, flextime_deci)
            VALUES (?, ?, ?, ?)
            """;

        private static final String SQL_FIND_BY_ID = """
            SELECT timesheet_id, employee_id, end_date, overtime_deci, flextime_deci, created_at
            FROM timesheets
            WHERE timesheet_id = ?
            """;

        private static final String SQL_FIND_BY_EMP = """
            SELECT timesheet_id, employee_id, end_date, overtime_deci, flextime_deci, created_at
            FROM timesheets
            WHERE employee_id = ?
            ORDER BY end_date DESC
            """;

        private static final String SQL_UPDATE_META = """
            UPDATE timesheets
            SET end_date = ?, overtime_deci = ?, flextime_deci = ?
            WHERE timesheet_id = ?
            """;

        private static final String SQL_DELETE = """
            DELETE FROM timesheets
            WHERE timesheet_id = ?
            """;

        private static final String SQL_FIND_CURRENT_FOR_EMP = """
            SELECT timesheet_id, employee_id, end_date, overtime_deci, flextime_deci, created_at
            FROM timesheets
            WHERE employee_id = ?
            ORDER BY ABS(DATEDIFF(end_date, CURRENT_DATE())),
                     (end_date < CURRENT_DATE()) ASC
            LIMIT 1
            """;
        
        public long create(long employeeId, LocalDate endDate, int overtimeDeci, int flextimeDeci) throws SQLException {
            try (Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS)){
                ps.setLong(1, employeeId);
                ps.setDate(2, Date.valueOf(endDate));
                ps.setInt(3, overtimeDeci);
                ps.setInt(4, flextimeDeci);
                ps.executeUpdate();
                
                try(ResultSet keys = ps.getGeneratedKeys()){
                    if(keys.next()) return keys.getLong(1);
                    throw new SQLException("Failed to get generated timesheet_id");
                }
            }
        }
        
        public TimesheetRecord findById(long tsId) throws SQLException{
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_ID)){
                ps.setLong(1, tsId);
                try(ResultSet rs = ps.executeQuery()){
                    if(!rs.next()) return null;
                    return map(rs);
                }
            }
        }
        
        public List<TimesheetRecord> findByEmployee(long employeeId) throws SQLException{
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_EMP)){
                ps.setLong(1, employeeId);
                try(ResultSet rs = ps.executeQuery()){
                    List<TimesheetRecord> out = new ArrayList<>();
                    while(rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        }
        
        public TimesheetRecord findCurrentForEmployee(long employeeId) throws SQLException{
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_FIND_CURRENT_FOR_EMP)){
                ps.setLong(1, employeeId);
                try(ResultSet rs = ps.executeQuery()){
                    if(!rs.next()) return null;
                    return map(rs);
                }
            }
        }
        
        public void updateMeta(long tsId, LocalDate endDate, int overtimeDeci, int flextimeDeci) throws SQLException{
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_UPDATE_META)){
                ps.setDate(1, Date.valueOf(endDate));
                ps.setInt(2, overtimeDeci);
                ps.setInt(3, flextimeDeci);
                ps.setLong(4, tsId);
                ps.executeUpdate();
            }
        }
        
        public void delete(long tsId) throws SQLException{
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_DELETE)){
                ps.setLong(1, tsId);
                ps.executeUpdate();
            }
        }
        
        
        
        
        private static TimesheetRecord map(ResultSet rs) throws SQLException{
            return new TimesheetRecord(
                    rs.getLong("timesheet_id"),
                    rs.getLong("employee_id"),
                    rs.getDate("end_date").toLocalDate(),
                    rs.getInt("overtime_deci"),
                    rs.getInt("flextime_deci"),
                    rs.getTimestamp("created_at")
                    );
        }
    

}
