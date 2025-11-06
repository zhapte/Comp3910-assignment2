package com.corejsf;

import jakarta.enterprise.context.Conversation;
import jakarta.enterprise.context.ConversationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;

/**
 * Managed bean responsible for handling password change functionality.
 *
 * <p>Scope: {@link ConversationScoped} — persists across multiple requests
 * while the user is on the password change page, and ends when the operation completes.</p>
 *
 * <p>This bean validates password inputs, checks the user’s current password,
 * and updates it in {@link EmployeeRepo}. It also provides user feedback via JSF
 * {@link FacesMessage}s.</p>
 */
@Named("changePasswordBean")
@ConversationScoped
public class ChangePasswordBean implements Serializable{
    
    /** Repository containing employees and their credentials. */

	@Inject
    private EmployeeRepo employees;  
	
	 /** Represents the current logged-in user. */
	@Inject
	private CurrentUser currentUser;
	
	/** CDI conversation used to maintain state during the password change flow. */
	@Inject 
	private Conversation conversation;
	
	/** Current password entered by the user. */
	private String currentPw;
	
    /** New password the user wants to set. */
	private String newPw;
	
	/** Confirmation of the new password to ensure consistency. */
	private String confirmPw;
	
	/**
     * @return current password entered by the user
     */
	public String getCurrentPw() { return currentPw; }
	
	/**
     * @param currentPw current password input
     */
    public void setCurrentPw(String currentPw) { this.currentPw = currentPw; }
    
    /**
     * @return new password entered by the user
     */
    public String getNewPw() { return newPw; }
    
    /**
     * @param newPw new password input
     */
    public void setNewPw(String newPw) { this.newPw = newPw; }
    
    /**
     * @return confirmation password entered by the user
     */
    public String getConfirmPw() { return confirmPw; }
    
    /**
     * @param confirmPw confirmation password input
     */
    public void setConfirmPw(String confirmPw) { this.confirmPw = confirmPw; }
	
    /**
     * Begins a CDI conversation if one is not already active.
     *
     * <p>This keeps the bean alive across multiple HTTP requests
     * during the password change process.</p>
     */
	public void begin() {
        if (conversation.isTransient()) {
            conversation.begin();
        }
    }

	/**
     * Ends the current CDI conversation if active.
     *
     * <p>Called once the password has been successfully changed
     * or the user navigates away.</p>
     */
    public void end() {
        if (!conversation.isTransient()) {
            conversation.end();
        }
    }
	
    /**
     * Validates inputs, verifies the current password, and updates it if valid.
     *
     * <p>Steps performed:</p>
     * <ol>
     *   <li>Ensures all fields are filled in.</li>
     *   <li>Checks that new password and confirmation match.</li>
     *   <li>Checks minimum password length (6 characters).</li>
     *   <li>Validates the current password against the stored credentials.</li>
     *   <li>Updates the password in the {@link EmployeeRepo} if validation succeeds.</li>
     * </ol>
     *
     * <p>On failure, displays an appropriate {@link FacesMessage} and stays on the same page.</p>
     *
     * @return navigation outcome "userHome" on success, or {@code null} on validation failure
     */
	public String changePassword(){
		begin();
		
		if (isBlank(currentPw) || isBlank(newPw) || isBlank(confirmPw)) {
            addMsg(FacesMessage.SEVERITY_ERROR, "Missing fields", "Fill out all password fields.");
            return null;
        }
        if (!newPw.equals(confirmPw)) {
            addMsg(FacesMessage.SEVERITY_ERROR, "Mismatch", "New password and confirmation do not match.");
            return null;
        }
        if (newPw.length() < 6) {
            addMsg(FacesMessage.SEVERITY_WARN, "Too short", "Use at least 6 characters.");
            return null;
        }
		
		Credentials cred = new Credentials();
        cred.setUserName(currentUser.getEmployee().getUserName());
        cred.setPassword(currentPw);
		
		boolean ok = employees.verifyUser(cred);
        if (!ok) {
            addMsg(FacesMessage.SEVERITY_ERROR, "Incorrect password", "Your current password is wrong.");
            return null;
        }
		
		try {
            employees.changePassword(cred.getUserName(), newPw);
            end();
            return "userHome";
        } catch (RuntimeException ex) {
            addMsg(FacesMessage.SEVERITY_ERROR, "Error", ex.getMessage());
            return null;
        }
	}
	
	/**
     * Checks whether a string is null, empty, or consists only of whitespace.
     *
     * @param s string to check
     * @return {@code true} if blank; {@code false} otherwise
     */
	private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

	/**
     * Adds a {@link FacesMessage} to the current JSF context.
     *
     * @param sev     severity (e.g., ERROR, WARN, INFO)
     * @param summary short summary for the message header
     * @param detail  detailed explanation to display to the user
     */
    private void addMsg(FacesMessage.Severity sev, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(sev, summary, detail));
    }
}