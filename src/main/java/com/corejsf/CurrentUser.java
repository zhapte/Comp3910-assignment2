package com.corejsf;

import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Named;
import java.io.Serializable;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;

/**
 * Session-scoped bean that holds information about the currently logged-in user.
 *
 * <p>Scope: {@link SessionScoped} — persists across multiple requests for the duration
 * of a single user's session.</p>
 *
 * <p>This bean stores the authenticated {@link Employee} and the currently
 * selected {@link Timesheet}, providing convenient helper methods for checking
 * login status and user roles.</p>
 */
@Named("currentUser")
@SessionScoped
public class CurrentUser implements Serializable {
    
    /** The authenticated employee for the active session. */
    private Employee employee;
	
    /** The timesheet currently selected or being viewed by the user. */
	private Timesheet selectedTimesheet;

	/**
     * Returns the current logged-in {@link Employee}.
     *
     * @return the employee object, or {@code null} if no user is logged in
     */
    public Employee getEmployee() {
        return employee;
    }
    
    /**
     * Sets the currently logged-in {@link Employee}.
     *
     * @param employee the authenticated employee object
     */
    public void setEmployee(Employee employee) {
        this.employee = employee;
    }
    
    /**
     * Checks whether a user is logged in.
     *
     * @return {@code true} if a user is authenticated, {@code false} otherwise
     */
    public boolean isLoggedIn(){return employee != null;}
    
    /**
     * Checks whether the current user is an administrator.
     *
     * @return {@code true} if the logged-in user is an instance of {@link Admin}, otherwise {@code false}
     */
    public boolean isAdmin(){return employee instanceof Admin;}
    
    /**
     * Returns the display name of the current user.
     *
     * @return user's name, or {@code null} if no user is logged in
     */
    public String getUserName(){return employee.getName();}
	
    /**
     * Returns the currently selected {@link Timesheet}.
     *
     * @return the selected timesheet, or {@code null} if none
     */
	public Timesheet getSelectedTimesheet() { return selectedTimesheet; }
	
	/**
     * Sets the currently selected {@link Timesheet}.
     *
     * @param ts the timesheet to associate with the current user
     */
	public void setSelectedTimesheet(Timesheet ts) { this.selectedTimesheet = ts; }
	
	/**
     * Clears the selected timesheet (sets it to {@code null}).
     *
     * <p>Called when navigating away from a timesheet view or logging out
     * to prevent stale data references.</p>
     */
    public void clearSelectedTimesheet() { this.selectedTimesheet = null; }
}
