package com.corejsf;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.annotation.PostConstruct;

import java.io.Serializable;
import java.util.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;

/**
 * Repository for in-memory Timesheet data.
 *
 * <p>Scope: {@link ApplicationScoped} — a single shared instance for the app lifetime.
 * Seeds some demo data at startup and provides query helpers to fetch a user's
 * timesheets, the "current" timesheet, and to create/save new ones.</p>
 *
 * <p>Implements {@link TimesheetCollection} so it can be injected wherever that
 * interface is required.</p>
 */
@Named("timeSheetRepo")
@ApplicationScoped
public class TimeSheetRepo  implements TimesheetCollection, Serializable {

    /** Backing store for all timesheets kept in memory. */
    private final List<Timesheet> all = new ArrayList<>();

    /** Current session user (used to derive 'my' timesheets). */
    @Inject
    private CurrentUser currentUser;

    /**
     * Returns a defensive copy of all timesheets.
     * @return list copy so callers can't mutate internal state directly
     */
    @Override
    public List<Timesheet> getTimesheets() {
        return new ArrayList<>(all);
    }

    /**
     * Container lifecycle hook that runs once after construction and injection.
     * Seeds three weeks of demo timesheets for a "System" admin user, each with 5 rows
     * and standard 8h Mon–Fri entries.
     */
    @PostConstruct
    public void startup() {
        Employee owner = makeSystemEmployee();

        LocalDate friday = LocalDate.now().with(java.time.DayOfWeek.FRIDAY);
		
        for (int w = 0; w < 3; w++) {
            Timesheet ts = new Timesheet(owner, friday.minusWeeks(w));
            for (int i = 0; i < 5; i++) {
                TimesheetRow row = new TimesheetRow();
                row.setProjectId(100 + i);
                row.setWorkPackageId("WP-" + (i + 1));
                row.setHours(new float[]{8, 8, 8, 8, 8, 0, 0});
                ts.getDetails().add(row);
            }
            ts.setOvertime(0);
            ts.setFlextime(0);
            all.add(ts);
		}
    }
    
    /**
     * Builds a minimal admin "system" employee used to own seeded data.
     */
    private Employee makeSystemEmployee() {
        Admin admin = new Admin();
        admin.setUserName("admin");
        admin.setEmpNumber(0);
        admin.setName("System");
        return admin;
    }
    
    /**
     * Returns all timesheets belonging to a specific employee, newest end dates first.
     * @param e employee whose timesheets to fetch (null returns empty list)
     */
    @Override
    public List<Timesheet> getTimesheets(Employee e){
        if (e == null) return Collections.emptyList();
        final List<Timesheet> out = new ArrayList<>();
        final int empNo = e.getEmpNumber();
        for (Timesheet ts : all) {
            if (ts.getEmployee() != null && ts.getEmployee().getEmpNumber() == empNo) {
                out.add(ts);
            }
        }
        out.sort(Comparator.comparing(Timesheet::getEndDate).reversed());
        return out;
    }

    /**
     * Heuristically picks the "current" timesheet for an employee:
     * the one whose end date is closest to today (ties broken by preferring
     * not-in-the-past to reduce surprises).
     *
     * @param e employee of interest
     * @return the nearest timesheet by end date, or null if none exist
     */
    @Override
    public Timesheet getCurrentTimesheet(Employee e) {
        List<Timesheet> list = getTimesheets(e);
		if (list.isEmpty()) return null;

		LocalDate today = LocalDate.now();

		Comparator<Timesheet> byAbsDays =
            Comparator.comparingLong(ts -> Math.abs(ChronoUnit.DAYS.between(ts.getEndDate(), today)));
		Comparator<Timesheet> tieBreaker =
            Comparator.comparing(ts -> ts.getEndDate().isBefore(today));
			
		return list.stream()
            .filter(ts -> ts.getEndDate() != null)
            .min(byAbsDays.thenComparing(tieBreaker))
            .orElse(list.getFirst());
    }

    /**
     * Creates a new blank timesheet for the current user, with 5 empty rows,
     * adds it to the repository, and returns a simple status string.
     * @return "created" on success
     */
    @Override
    public String addTimesheet() {
        Timesheet ts = new Timesheet();
		Employee me = currentUser.getEmployee();
        ts.setEmployee(me);

        for (int i = 0; i < 5; i++) {
            ts.addRow();
        }
		
        all.add(ts);
        return "created";
    }

    /**
     * Convenience: current user's "current" timesheet (see {@link #getCurrentTimesheet}).
     */
    public Timesheet getMyCurrentTimesheet() {
        return getCurrentTimesheet(currentUser.getEmployee());
    }

    /**
     * Convenience: all timesheets for the current user.
     */
    public List<Timesheet> getMyTimesheets() {
        return getTimesheets(currentUser.getEmployee());
    }
    
    /**
     * Saves a timesheet if non-null and not already present.
     * Acts as an upsert into the in-memory list.
     */
    public void save(Timesheet ts) {
        if(ts == null) return;
        if(!all.contains(ts)) {
            all.add(ts);
        }
    }
	
    /**
     * Returns the newest (max end date) timesheet for the current user.
     * <p><b>Note:</b> Assumes the user has at least one timesheet; callers should ensure
     * non-emptiness or handle {@code NoSuchElementException} if adapting this logic.</p>
     */
	public Timesheet getMyNewest(){
		Employee me = currentUser.getEmployee();
		int empNo = me.getEmpNumber();

		List<Timesheet> mine = all.stream()
            .filter(ts -> ts.getEmployee() != null && ts.getEmployee().getEmpNumber() == empNo)
            .toList();

		return mine.getLast();
	}
    
	
}