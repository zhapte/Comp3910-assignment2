package com.corejsf;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.Conversation;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;

/**
 * Navigation controller bean for handling page transitions and session cleanup.
 *
 * <p>Scope: {@link RequestScoped} — created fresh for each HTTP request.</p>
 *
 * <p>This bean centralizes all navigation logic between pages such as dashboards,
 * timesheets, password change, and logout. It also ensures that any active
 * conversation scope is properly ended before navigating away.</p>
 */
@Named("navBean")
@RequestScoped
public class NavBean implements Serializable{
	
    /** CDI conversation for managing multi-request scoped beans. */
	@Inject
	private Conversation conversation;
	
	/** Handles user authentication and logout operations. */
	@Inject
	private LoginBean login;
	
	/** Repository for accessing and managing timesheets. */
	@Inject
	private TimeSheetRepo timesheetrepo;
	
	/** Represents the current logged-in user and their state. */
	@Inject
	private CurrentUser currentuser;
	
	/**
     * Ends the current CDI conversation if active and clears the user's selected timesheet.
     *
     * <p>This method is called before navigating to a new page to ensure
     * a clean state and prevent conversation leaks. Any {@link IllegalStateException}
     * (e.g., when no active conversation exists) is safely ignored.</p>
     */
	public void end(){
		try {

            conversation.end();

        } catch (IllegalStateException ignored) {
         
        }finally{
			currentuser.clearSelectedTimesheet();
		}
	}
	
	/**
     * Navigates to the regular user's dashboard.
     * <p>Ends any active conversation and clears selected timesheet data.</p>
     *
     * @return JSF navigation outcome "userHome"
     */
	public String dashBoard(){
		end();
		return "userHome";
	}
	
	/**
     * Navigates to the admin dashboard page.
     * <p>Ends any active conversation and resets user-specific data.</p>
     *
     * @return JSF navigation outcome "adminHome"
     */
	public String adminDashBoard(){
		end();
		return "adminHome";
	}
	
	/**
     * Navigates to the change password page.
     * <p>Ends the current conversation to ensure no stale user data persists.</p>
     *
     * @return JSF navigation outcome "changePassword"
     */
	public String changePassword(){
		end();
		return "changePassword";
	}
	
	 /**
     * Navigates to the current timesheet form for the logged-in user.
     *
     * <p>Ends the current conversation, retrieves (or creates) the user’s
     * current timesheet from the repository, and sets it as the selected
     * timesheet in {@link CurrentUser}.</p>
     *
     * @return JSF navigation outcome "timesheetForm"
     */
	public String currentTimesheet(){
		end();
		Employee me = currentuser.getEmployee();
		Timesheet ts = timesheetrepo.getCurrentTimesheet(me);
		currentuser.setSelectedTimesheet(ts);
		return "timesheetForm";
	}
	
	/**
     * Navigates to the new timesheet creation page.
     *
     * <p>Ends the current conversation and clears any selected timesheet.
     * Uses a redirect to ensure a fresh page load.</p>
     *
     * @return navigation string with redirect to "timesheet-edit.xhtml"
     */
	public String newTimesheet(){
		end();
		currentuser.clearSelectedTimesheet();
		return "timesheet-edit.xhtml?faces-redirect=true&new=1";
	}
	
	/**
     * Logs out the current user and ends any active conversation.
     *
     * <p>Delegates to {@link LoginBean#logout()} to perform session cleanup
     * and invalidate the user’s session.</p>
     *
     * @return navigation outcome returned by the logout method (usually the login page)
     */
	public String logout(){
		end();
		return login.logout();
	}
}