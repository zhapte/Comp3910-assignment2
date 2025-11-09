package com.corejsf;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class TimesheetRowDao {
    
    @Resource(lookup = "java:/jdbc/timesheetsDS")
    private DataSource ds;
    
    public static final class RowRecord{
        public final long rowId;
        public final long timesheetId;
        public final int lineNo;
        public final int projectId;
        public final String workPackageId;
        public final long packedHours;
        public final String notes;
        
        public RowRecord(long rowId,
                long timesheetId,
                int lineNo,
                int projectId,
                String workPackageId,
                long packedHours,
                String notes)
        {
            this.rowId = rowId;
            this.timesheetId = timesheetId;
            this.lineNo = lineNo;
            this.projectId = projectId;
            this.workPackageId = workPackageId;
            this.packedHours = packedHours;
            this.notes = notes;
        }
    }
    
    //queries
    private static final String SQL_SELECT_BY_TS = """
            SELECT row_id, timesheet_id, line_no, project_id, work_package_id, packed_hours, notes
            FROM timesheet_rows
            WHERE timesheet_id = ?
            ORDER BY line_no
            """;

        private static final String SQL_INSERT = """
            INSERT INTO timesheet_rows (timesheet_id, line_no, project_id, work_package_id, packed_hours, notes)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        private static final String SQL_UPDATE = """
            UPDATE timesheet_rows
            SET line_no = ?, project_id = ?, work_package_id = ?, packed_hours = ?, notes = ?
            WHERE row_id = ?
            """;

        private static final String SQL_DELETE_BY_TS = """
            DELETE FROM timesheet_rows WHERE timesheet_id = ?
            """;

        private static final String SQL_DELETE_ROW = """
            DELETE FROM timesheet_rows WHERE row_id = ?
            """;
        
        public List<RowRecord> findByTimesheet(long tsId) throws SQLException{
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_SELECT_BY_TS)){
                ps.setLong(1, tsId);
                try(ResultSet rs = ps.executeQuery()){
                    List<RowRecord> out = new ArrayList<>();
                    while(rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        }
        
        public long insert(long tsId, int lineNo, int projectId, String workPackageId, float[] hours, String notes) throws SQLException{
            long packed = packHours(hours);
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS)){
                ps.setLong(1, tsId);
                ps.setInt(2, lineNo);
                ps.setInt(3, projectId);
                ps.setString(4, workPackageId != null ? workPackageId : "");
                ps.setLong(5, packed);
                ps.setString(6, notes);
                ps.executeUpdate();
                try(ResultSet keys = ps.getGeneratedKeys()){
                    if(keys.next()) return keys.getLong(1);
                    throw new SQLException("Failed to get generated row_id");
                }
            }
                
        }
        
        public void update(long rowId, int lineNo, int projectId, String workPackageId, float[] hours, String notes) throws SQLException{
            long packed = packHours(hours);
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_UPDATE)){
                ps.setInt(1, lineNo);
                ps.setInt(2, projectId);
                ps.setString(3, workPackageId != null ? workPackageId : "");
                ps.setLong(4, packed);
                ps.setString(5, notes);
                ps.setLong(6, rowId);
                ps.executeUpdate();
            }
        }
        
        public void deleteRowsByTimesheet(long tsId) throws SQLException {
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_DELETE_BY_TS)){
                ps.setLong(1, tsId);
                ps.executeUpdate();
            }
        }
        
        public void deleteRow(long rowId) throws SQLException{
            try(Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement(SQL_DELETE_ROW)){
                ps.setLong(1, rowId);
                ps.executeUpdate();
            }
        }
            
        private static RowRecord map(ResultSet rs) throws SQLException{
            return new RowRecord(
                rs.getLong("row_id"),
                rs.getLong("timesheet_id"),
                rs.getInt("line_no"),
                rs.getInt("project_id"),
                rs.getString("work_package_id"),
                rs.getLong("packed_hours"),
                rs.getString("notes")
                );
            
        }
        
        public static long packHours(float[] hours) {
            int len = Math.min(hours == null ? 0 : hours.length, 7);
            long v = 0L;
            for(int i = 0; i < 7; i++) {
                int tenths = 0;
                if(i < len && !Float.isNaN(hours[i])) {
                    float clamped = Math.max(0f, Math.min(24f, hours[i]));
                    tenths = Math.round(clamped * 10f);
                }
                v |= ((long) (tenths & 0xFF)) << (i * 8);
            }
            return v;
        }
        
        public static float[] unpackHours(long packed) {
            float[] out = new float[7];
            for(int i = 0; i < 7; i++) {
                int tenths = (int) ((packed >>> (i * 8)) & 0xFF);
                out[i] = tenths / 10.0f;
            }
            return out;
        }
    
 

}
