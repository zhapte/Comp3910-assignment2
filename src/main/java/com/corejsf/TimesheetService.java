/**
package com.corejsf;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.corejsf.TimesheetDao.TimesheetRecord;
import com.corejsf.TimesheetRowDao.RowRecord;

import ca.bcit.infosys.employee.Employee;
import ca.bcit.infosys.timesheet.Timesheet;
import ca.bcit.infosys.timesheet.TimesheetRow;

@ApplicationScoped
public class TimesheetService {

    @Inject private TimesheetDao tsDao;
    @Inject private TimesheetRowDao rowDao;

    
    public Timesheet createFor(final Employee owner, final LocalDate endDate) {
        try {
           
            final long ownerKey = owner.getEmpNumber();
            final long tsId = tsDao.create(ownerKey, endDate, 0, 0);

            
            for (int i = 0; i < 5; i++) {
                rowDao.insert(tsId, i + 1, 0, "", new float[]{0,0,0,0,0,0,0}, "");
            }
            return load(tsId, owner);
        } catch (Exception e) {
            return null;
        }
    }

   
    public Timesheet load(final long tsId, final Employee owner) {
        try {
            final TimesheetRecord r = tsDao.findById(tsId);
            if (r == null) return null;

            final Timesheet t = new Timesheet(owner, r.endDate);

            final List<RowRecord> rows = rowDao.findByTimesheet(tsId);
            for (RowRecord rr : rows) {
                TimesheetRow tr = new TimesheetRow();
                tr.setProjectId(rr.projectId);
                tr.setWorkPackageId(rr.workPackageId);
                tr.setHours(TimesheetRowDao.unpackHours(rr.packedHours));
                tr.setNotes(rr.notes);
                t.getDetails().add(tr);
            }

           
            t.setOvertime(r.overtimeDeci / 10.0f);
            t.setFlextime(r.flextimeDeci / 10.0f);
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    
    public void save(final long tsId, final Timesheet t) {
        try {
            tsDao.updateMeta(tsId, t.getEndDate(), t.getOvertimeDecihours(), t.getFlextimeDecihours());

            rowDao.deleteRowsByTimesheet(tsId);
            final List<TimesheetRow> rows = t.getDetails();
            for (int i = 0; i < rows.size(); i++) {
                TimesheetRow r = rows.get(i);
                rowDao.insert(tsId, i + 1, r.getProjectId(), r.getWorkPackageId(), r.getHours(), r.getNotes());
            }
        } catch (Exception ignore) {}
    }


    public List<Timesheet> listFor(final Employee owner) {
        final List<Timesheet> out = new ArrayList<>();
        try {
            final long ownerKey = owner.getEmpNumber();
            for (TimesheetRecord rec : tsDao.findByEmployee(ownerKey)) {
                Timesheet t = load(rec.timesheetId, owner);
                if (t != null) out.add(t);
            }
        } catch (Exception ignore) {}
        return out;
    }


    public Timesheet loadCurrent(final Employee owner) {
        try {
            final long ownerKey = owner.getEmpNumber();
            final TimesheetRecord rec = tsDao.findCurrentForEmployee(ownerKey);
            return (rec == null) ? null : load(rec.timesheetId, owner);
        } catch (Exception e) {
            return null;
        }
    }
}

*/