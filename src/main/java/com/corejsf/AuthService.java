package com.corejsf;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;

/**
 * Authentication service responsible for verifying user credentials
 * and retrieving corresponding {@link Employee} records.
 *
 * <p>Scope: {@link ApplicationScoped} — shared across the entire application.</p>
 *
 * <p>This service acts as a middle layer between the login process
 * and the {@link EmployeeRepo}, encapsulating credential validation
 * and basic role-checking logic.</p>
 */
@Named("authService")
@ApplicationScoped
public class AuthService implements Serializable{
	
    /** Repository that stores employees and their login credentials. */
	@Inject
	private EmployeeRepo employees;
	
	/**
     * Attempts to authenticate a user using their provided credentials.
     *
     * <p>If the credentials are valid, retrieves and returns the corresponding
     * {@link Employee} object from the repository. If authentication fails,
     * returns {@code null}.</p>
     *
     * @param creds the {@link Credentials} object containing username and password
     * @return the matching {@link Employee} if credentials are valid; {@code null} otherwise
     */
	public Employee authenticate(Credentials creds){
		if (creds == null){
			return null;
		}
		
		boolean logged = employees.verifyUser(creds);
		if(!logged){
			return null;
		}
		
		return findByUserName(creds.getUserName());
	}
	
	/**
     * Determines whether a given {@link Employee} has administrator privileges.
     *
     * @param e the employee to check
     * @return {@code true} if the employee is an instance of {@link Admin}; {@code false} otherwise
     */
	public boolean isAdmin(Employee e) {
        return e instanceof Admin;
    }
	
	/**
     * Finds and returns an {@link Employee} by username.
     *
     * <p>Performs a case-insensitive search through all stored employees.</p>
     *
     * @param userName the username to search for
     * @return the matching employee, or {@code null} if not found
     */
	public Employee findByUserName(String userName) {
        return employees.getEmployees().stream()
                .filter(e -> e.getUserName().equalsIgnoreCase(userName))
                .findFirst()
                .orElse(null);
    }
}