package com.corejsf;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;

/**
 * Backing bean for the "Current Timesheet" JSF view.
 *
 * <p>Scope: {@link RequestScoped} — created fresh for each page request.</p>
 *
 * <p>This bean loads (or lazily creates) the current user's latest timesheet
 * through {@link TimeSheetRepo}, and exposes it to the Facelets view layer
 * for display and binding.</p>
 */
@Named("timesheetView")
@RequestScoped
public class TimeSheetViewBean implements Serializable {
    
    /** Repository for accessing and creating timesheets. */
    @Inject private TimeSheetRepo timeSheetRepo;

    /** The currently displayed timesheet. */
    private Timesheet sheet;

    /**
     * Initializes the bean each time the page is requested.
     *
     * <p>Attempts to retrieve the current user's most recent timesheet;
     * if none exists yet, it creates one automatically.</p>
     */
    public void init() {
        // Try to get current user's most recent sheet
        sheet = timeSheetRepo.getMyCurrentTimesheet();
        if (sheet == null) {
            // If none exists yet for this (logged-in) user, create one
            timeSheetRepo.addTimesheet();
            sheet = timeSheetRepo.getMyCurrentTimesheet();
        }
    }

    
    /**
     * @return the current timesheet object for the view.
     */
    public Timesheet getSheet() { return sheet; }
    
    /**
     * @return list of timesheet rows, or an empty list if the sheet is null.
     */
    public List<TimesheetRow> getRows() { return sheet != null ? sheet.getDetails() : List.of(); }
    
    /**
     * @return employee number of the sheet owner as a string, or empty if unavailable.
     */
    public String getEmpNumber() {
        return (sheet != null && sheet.getEmployee() != null) ? String.valueOf(sheet.getEmployee().getEmpNumber()) : "";
    }
    
    /**
     * @return full name of the employee, or empty string if not set.
     */
    public String getEmployeeName() {
        return (sheet != null && sheet.getEmployee() != null) ? sheet.getEmployee().getName() : "";
    }
    
    /**
     * @return numeric week number for this timesheet, or null if none.
     */
    public Integer getWeekNumber() { return sheet != null ? sheet.getWeekNumber() : null; }
    
    /**
     * @return week-ending (Friday) date for the timesheet, or null if none.
     */
    public LocalDate getWeekEnding() { return sheet != null ? sheet.getEndDate() : null; }

    /**
     * @return total number of hours recorded across all rows in this timesheet.
     */
    public float getGrandTotal() { return sheet == null ? 0f : sheet.getTotalHours(); }
    
    /**
     * @param dayIndex index of the day (0 = Saturday ... 6 = Friday)
     * @return total hours recorded for that specific day.
     */
    public float getDayTotal(int dayIndex) {
        if (sheet == null) return 0f;
        return sheet.getDailyHours()[dayIndex];
    }
}
