package com.corejsf;

import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;

/**
 * Managed bean responsible for handling user authentication and session management.
 *
 * <p>Scope: {@link SessionScoped} — remains active for the duration of the user's session.</p>
 *
 * <p>This bean coordinates with {@link AuthService} to verify credentials and stores
 * the authenticated user in {@link CurrentUser}. It also provides logout and session
 * validation methods for navigation control.</p>
 */
@Named("loginBean")
@SessionScoped
public class LoginBean implements Serializable {

    /** Service responsible for verifying login credentials. */
	@Inject
	private AuthService authService;

	/** Represents the currently logged-in user (session-scoped context). */
    @Inject
    private CurrentUser currentUser;

    /** Stores the username and password entered by the user. */
    private Credentials cred = new Credentials();

    /**
     * Returns the user's entered credentials object.
     * <p>Used for JSF form data binding (e.g., login.xhtml inputs).</p>
     *
     * @return the {@link Credentials} containing username and password
     */
    public Credentials getCred() {
        return cred;
    }

    /**
     * Sets the user's credential information.
     * <p>Usually set automatically by JSF when the login form is submitted.</p>
     *
     * @param cred new credentials to assign
     */
    public void setCred(Credentials cred) {
        this.cred = cred;
    }

    /**
     * Attempts to authenticate the user using the {@link AuthService}.
     *
     * <p>If authentication fails, an error message is displayed using
     * {@link FacesContext}, and the method returns {@code null} to remain
     * on the current page.</p>
     *
     * <p>If successful, the authenticated {@link Employee} is stored in
     * {@link CurrentUser}, and navigation proceeds to the user dashboard.</p>
     *
     * @return navigation outcome "userHome" if login succeeds, or null if it fails
     */
    public String login(){
        if (cred != null && cred.getUserName() != null) {
            cred.setUserName(cred.getUserName().trim());
        }

        final Employee emp = authService.authenticate(cred);
        if (emp == null) {
            FacesContext.getCurrentInstance().addMessage(
                null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Login failed", "Invalid username or password")
            );
            return null;
        }

        currentUser.setEmployee(emp);
        currentUser.clearSelectedTimesheet();

        cred = new Credentials();

        return "userHome";
    }

    /**
     * Logs out the current user by invalidating the active HTTP session.
     *
     * <p>Clears all session-scoped beans and returns the user to the login page.</p>
     *
     * @return navigation outcome "login"
     */
    public String logout(){
        FacesContext.getCurrentInstance()
                .getExternalContext()
                .invalidateSession();

        return "login";
    }

    /**
     * Ensures that a user is currently logged in.
     *
     * <p>This method can be called via `<f:viewAction>` on restricted pages
     * to redirect unauthenticated users back to the login page.</p>
     *
     * @return "login" if no user is logged in, otherwise null (stay on current page)
     */
    public String ensureLoggedIn() {
        if (!currentUser.isLoggedIn()) {
            return "login";
        }
        return null;
    }
}
