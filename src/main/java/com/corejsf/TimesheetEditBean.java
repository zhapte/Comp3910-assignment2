package com.corejsf;

import jakarta.enterprise.context.ConversationScoped;
import jakarta.enterprise.context.Conversation;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.lang.Float;
import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;

/**
 * Managed bean for editing a single timesheet.
 *
 * <p>Scope: {@link ConversationScoped} — persists across multiple requests while the
 * user edits a timesheet, and ends when saved or navigated away.</p>
 *
 * <p>This bean loads (or creates) a timesheet for the current user, provides
 * editable data grids for the hours and notes, and saves changes back into
 * the in-memory {@link TimeSheetRepo}.</p>
 */
@Named("timesheetEdit")
@ConversationScoped
public class TimesheetEditBean implements Serializable {

    /** Repository that stores all timesheets in memory. */
    @Inject 
    private TimeSheetRepo timeSheetRepo;
    
    /** CDI conversation to maintain multi-request editing sessions. */
    @Inject
    private Conversation conversation;

    /** The current logged-in user context. */
	@Inject
	private CurrentUser currentUser;

	/** The timesheet currently being edited. */
    private Timesheet sheet;
    
    /** Backing list of timesheet rows (projects/work packages). */
    private final List<TimesheetRow> rows = new ArrayList<>();

    /** Editable grid of hour strings (7 columns: Sat..Fri for each row). */
    private final List<List<String>> hoursGrid = new ArrayList<>();
    
    /** Parallel list of editable notes corresponding to each row. */
    private final List<String> notesGrid = new ArrayList<>();
	
    /** Optional target week ending date for loading or creating a timesheet. */
	private LocalDate targetDate;
	
	private Long tsId; 

	
	private static final String[] DAY_NAMES = {"Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri"};

	/**
     * Returns the target week-ending date associated with the timesheet.
     * <p>This value can be used to identify which week's timesheet
     * the user intends to view or edit.</p>
     *
     * @return the selected target {@link LocalDate}, or null if not set
     */
    public LocalDate getTargetDate() { return targetDate; }
    
    /**
     * Sets the target week-ending date for this timesheet.
     * <p>Typically called by the JSF framework when the user selects
     * a specific week from the UI.</p>
     *
     * @param targetDate the {@link LocalDate} representing the desired week-ending date
     */
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }

    /**
     * Initializes the editing session and builds editable grids from the model.
     *
     * <p>Steps performed:</p>
     * <ul>
     *   <li>Starts a conversation if not already active (20-minute timeout).</li>
     *   <li>Loads the selected timesheet from {@link CurrentUser}, or creates a new one if none exists.</li>
     *   <li>Ensures at least 5 rows exist.</li>
     *   <li>Builds editable grids (hours and notes) from the current timesheet rows.</li>
     * </ul>
     */
	@PostConstruct
    public void init() {
        if (conversation.isTransient()) {
            conversation.begin();
            conversation.setTimeout(20 * 60 * 1000L);
        }
        if (sheet != null) return; 
        sheet = currentUser.getSelectedTimesheet();
        if (sheet == null) {
            timeSheetRepo.addTimesheet();
            sheet = timeSheetRepo.getMyNewest();
        }
		currentUser.setSelectedTimesheet(sheet);
        while (sheet.getDetails().size() < 5) {
            sheet.addRow();
        }

        rows.clear();
        rows.addAll(sheet.getDetails());


        for (TimesheetRow r : rows) {
			float[] hrs = r.getHours(); 
			List<String> week = new ArrayList<>(7);
			for (int d = TimesheetRow.SAT; d <= TimesheetRow.FRI; d++) {

				week.add(hrs[d] == 0f ? "" : Float.toString(hrs[d]));
			}
			hoursGrid.add(week);
			notesGrid.add(r.getNotes());
		}

    }
	
	/**
     * Adds a blank row (project/work package) to the editable timesheet.
     * Also expands the hours and notes grids accordingly.
     *
     * @return navigation outcome string ("timesheetEdit") to remain on the edit page
     */
	public String addRow() {
        TimesheetRow r = new TimesheetRow();
        sheet.getDetails().add(r);
        rows.add(r);

        List<String> blankWeek = new ArrayList<>(Timesheet.DAYS_IN_WEEK);
        for (int i = 0; i < Timesheet.DAYS_IN_WEEK; i++) blankWeek.add("");
        hoursGrid.add(blankWeek);
        notesGrid.add("");
		
		return "timesheetEdit";
    }

	/**
     * Saves user-entered data from the editable grids back into the timesheet model.
     *
     * <p>Each string input is parsed into a float (0–24 range enforced)
     * and stored in the {@link TimesheetRow}. Notes are also updated.</p>
     *
     * @return navigation outcome "timesheetForm" (typically view mode)
     */
    public String save() {
		
		if (!validateTotalsFromGrid()) {
			return null; 
		}
		
		if (!validateUniqueProjectWP()) {
			return null;
		}
		
        for (int i = 0; i < rows.size(); i++) {
            TimesheetRow r = rows.get(i);
            List<String> week = hoursGrid.get(i);
            float[] pack = new float[7];
            for (int d = TimesheetRow.SAT; d <= TimesheetRow.FRI; d++) {
                String s = week.get(d);
                pack[d] = parseHour(s); 
            }
            r.setHours(pack);                 
            r.setNotes(notesGrid.get(i));
        }
		
		timeSheetRepo.save(sheet);
		
        return "timesheetForm";
    }
	
    /**
     * Parses a text string into a valid float hour value (0–24).
     *
     * <p>Blank or invalid input returns 0.0f to ensure numeric safety.</p>
     *
     * @param s string to parse
     * @return valid float between 0 and 24 inclusive
     */
	private float parseHour(String s) {
		if (s == null || s.isBlank()) return 0f;
		try {
			float v = Float.parseFloat(s.trim());
			if (v < 0f) v = 0f;
			if (v > 24f) v = 24f;
			return v;
		} catch (NumberFormatException ex) {
			return 0f;
		}
	}

	
	/** Ends the current CDI conversation if active. */
    private void endConv() {
        if (!conversation.isTransient()) conversation.end();
    }
	
    /**
     * Checks whether the current timesheet is editable.
     * A timesheet is considered editable if its end date is today or in the future.
     *
     * @return true if editing is allowed, false otherwise
     */
	public boolean isEditable() {
        if (sheet == null || sheet.getEndDate() == null) return false;
        java.time.LocalDate thisFriday =
            java.time.LocalDate.now().with(java.time.DayOfWeek.FRIDAY);
        return !sheet.getEndDate().isBefore(thisFriday);
    }

  
	/**
     * Returns the list of {@link TimesheetRow} objects that make up this timesheet.
     * <p>Each row typically represents a project or work package entry for the week.</p>
     *
     * @return list of timesheet rows currently being edited
     */
    public List<TimesheetRow> getRows() { return rows; }
    
    /**
     * Returns the editable grid of hours entered by the user.
     * <p>This is a two-dimensional list where each inner list contains
     * seven strings representing daily hours from Saturday through Friday.</p>
     *
     * @return 2D list of hour values as strings
     */
    public List<List<String>> getHoursGrid() { return hoursGrid; }
    
    /**
     * Returns the list of notes corresponding to each {@link TimesheetRow}.
     * <p>Each entry matches the row at the same index in the timesheet.</p>
     *
     * @return list of note strings for each row
     */
    public List<String> getNotesGrid() { return notesGrid; }
    
    /**
     * Returns the ISO week number associated with the current timesheet’s end date.
     * <p>If the timesheet or its end date is not set, 0 is returned.</p>
     *
     * @return numeric week number (1–52) or 0 if unavailable
     */
	public int getWeekNumber() {
		if (sheet == null || sheet.getEndDate() == null) return 0;
		java.time.temporal.WeekFields wf = java.time.temporal.WeekFields.of(java.util.Locale.getDefault());
		return sheet.getEndDate().get(wf.weekOfWeekBasedYear());
	}

	/**
     * Returns the current {@link Timesheet} object being edited.
     *
     * @return active timesheet, or null if none is loaded
     */
    public Timesheet getSheet() { return sheet; }
    
    /**
     * Returns the employee number of the user associated with this timesheet.
     * <p>If the employee or sheet is null, an empty string is returned.</p>
     *
     * @return employee number as a string, or empty if unavailable
     */
    public String getEmpNumber() {
        return sheet!=null && sheet.getEmployee()!=null ? String.valueOf(sheet.getEmployee().getEmpNumber()) : "";
    }
    
    /**
     * Returns the full name of the employee who owns this timesheet.
     * <p>If the employee or sheet is null, an empty string is returned.</p>
     *
     * @return employee's full name or empty string if not available
     */
    public String getEmployeeName() {
        return sheet!=null && sheet.getEmployee()!=null ? sheet.getEmployee().getName() : "";
    }
	
    /**
    * Validate per-day and weekly totals from the editable grid.
    * Emits {@link FacesMessage}s on failure.
    */
	private boolean validateTotalsFromGrid() {
		if (hoursGrid == null || hoursGrid.isEmpty()) return true;
	
		double[] dayTotals = new double[7];
	
		for (List<String> week : hoursGrid) {
			if (week == null) continue;
			for (int d = 0; d < 7; d++) {
				String s = (d < week.size()) ? week.get(d) : null;
				if (s == null || s.isBlank()) continue;
				try {
					double v = Double.parseDouble(s.trim());
					if (v < 0) v = 0;
					if (v > 24) v = 24;
					v = Math.round(v * 10.0) / 10.0;
					dayTotals[d] += v;
				} catch (NumberFormatException ignored) {

				}
			}
		}
	
		boolean valid = true;
	
		// Per-day cap
		for (int d = 0; d < 7; d++) {
			if (dayTotals[d] > 24.0 + 1e-6) {
				valid = false;
				jakarta.faces.context.FacesContext.getCurrentInstance().addMessage(
				    null, new jakarta.faces.application.FacesMessage(
				        jakarta.faces.application.FacesMessage.SEVERITY_ERROR,
				        String.format("Total for %s exceeds 24 hours (%.1f h).", DAY_NAMES[d], dayTotals[d]), null));
			}
		}
	
		// Weekly cap (optional)
		double weekTotal = Arrays.stream(dayTotals).sum();
		if (weekTotal > 168.0 + 1e-6) {
			valid = false;
			jakarta.faces.context.FacesContext.getCurrentInstance().addMessage(
			    null, new jakarta.faces.application.FacesMessage(
			        jakarta.faces.application.FacesMessage.SEVERITY_ERROR,
			        String.format("Weekly total exceeds 168 hours (%.1f h).", weekTotal), null));
		}
	
		return valid;
	}
	
	/**
	* Ensure each (projectId, workPackageId) pair is unique across rows.
	* Ignores rows that are entirely empty (projectId = 0 and blank WP).
	* Emits {@link FacesMessage}s describing the duplicates.
	*/
	private boolean validateUniqueProjectWP() {
		boolean ok = true;
		Map<String, Integer> firstSeenAtRow = new LinkedHashMap<>(); 
	
		for (int i = 0; i < rows.size(); i++) {
			TimesheetRow r = rows.get(i);
	
			int projectId = r.getProjectId(); 
			String wp = Optional.ofNullable(r.getWorkPackageId()).orElse("").trim();
	
			// Ignore “empty” rows (lets users keep spare rows)
			if (projectId == 0 && wp.isEmpty()) continue;
	
			// Normalize key (case-insensitive WP)
			String key = projectId + "||" + wp.toUpperCase(Locale.ROOT);
	
			Integer other = firstSeenAtRow.putIfAbsent(key, i + 1);
			if (other != null) {
				FacesContext.getCurrentInstance().addMessage(
					null,
					new FacesMessage(
						FacesMessage.SEVERITY_ERROR,
						String.format(
							"Duplicate Project/WP: project %d with WP \"%s\" appears in rows %d and %d.",
							projectId, wp, other, i + 1),
						null
					)
				);
				ok = false;
			}
		}
		return ok;
}
	
	/** @return the ID of the timesheet being edited (may be null). */
	public Long getTsId() { return tsId; }
	
	/**
	 * Sets the ID of the timesheet to load/edit.
	 * Typically populated via navigation parameters.
	 */
	public void setTsId(Long tsId) { this.tsId = tsId; }

}
