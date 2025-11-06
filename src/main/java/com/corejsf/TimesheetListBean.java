package com.corejsf;

import jakarta.enterprise.context.Conversation;
import jakarta.enterprise.context.ConversationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;

/**
 * Managed bean responsible for displaying and navigating between
 * multiple timesheets belonging to the current user.
 *
 * <p>Scope: {@link ConversationScoped} — lives across multiple requests
 * during a single “conversation” (e.g., viewing or editing a timesheet)
 * and ends when the conversation is explicitly closed.</p>
 *
 * <p>This bean determines which timesheets to show depending on
 * whether the logged-in user is an admin or a regular employee.</p>
 */
@Named("timesheetListBean")
@ConversationScoped
public class TimesheetListBean implements Serializable{
	
    /** Repository of all available timesheets (acts as in-memory database). */
	@Inject
	private TimeSheetRepo timesheets;
	
	/** The current logged-in user (used to filter timesheets). */
	@Inject 
	private CurrentUser currentUser;
	
	/** CDI conversation for managing multi-request scope. */
	@Inject 
	private Conversation conversation;
	
	/** The currently selected timesheet in the list view. */
	private Timesheet selected;
	
	/**
     * Begins a new conversation if none exists.
     *
     * <p>This ensures the bean stays active across multiple requests
     * (e.g., navigating between timesheet pages).</p>
     */
	public void begin() {
        if (conversation.isTransient()) {
            conversation.begin();
        }
    }

	/**
     * Ends the current conversation if it’s active.
     *
     * <p>Typically called when leaving the timesheet section
     * or after saving/closing a timesheet.</p>
     */
    public void end() {
        if (!conversation.isTransient()) {
            conversation.end();
        }
    }
	
    /**
     * Retrieves all timesheets relevant to the current user.
     *
     * <ul>
     *   <li>If the user is an admin, returns all timesheets in the system.</li>
     *   <li>If the user is a regular employee, returns only their own timesheets.</li>
     * </ul>
     *
     * @return list of timesheets to display in the UI
     */
	public List<Timesheet> getMyTimesheets() {
        begin();
        if (currentUser.isAdmin()) {
            return timesheets.getTimesheets();
        } else {
            return timesheets.getTimesheets(currentUser.getEmployee());
        }
    }
	
	/**
     * Handles navigation to a specific timesheet’s detail view.
     *
     * <p>Stores the selected timesheet in the {@link CurrentUser} context
     * so the target view (e.g., "timesheetForm.xhtml") can access it.</p>
     *
     * @param ts the timesheet the user selected
     * @return navigation outcome (page name) — "timesheetForm"
     */
	public String viewTimesheet(Timesheet ts) {
        currentUser.setSelectedTimesheet(ts);
        return "timesheetForm"; 
    }
	
	/**
     * @return the currently selected timesheet in the list.
     */
	public Timesheet getSelected() { return selected; }
	
	/**
     * @param selected the timesheet chosen by the user.
     */
    public void setSelected(Timesheet selected) { this.selected = selected; }
}