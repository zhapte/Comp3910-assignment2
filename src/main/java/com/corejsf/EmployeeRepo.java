package com.corejsf;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;

/**
 * In-memory repository for {@link Employee} and {@link Credentials}.
 *
 * <p>Scope: {@link ApplicationScoped} — a single instance for the lifetime of the application.</p>
 *
 * <p>This repo seeds a default admin and a sample user at startup, provides lookup
 * and CRUD operations on employees, stores simple username/password pairs, and
 * exposes helpers for authentication checks and password changes.</p>
 */
@Named("employeeRepo")
@ApplicationScoped
public class EmployeeRepo implements EmployeeList{
    
    /** In-memory list of all employees. */
	private final List<Employee> employees = new ArrayList<>();
	
	/** In-memory list of credential records (username/password pairs). */
	private final List<Credentials> credentials = new ArrayList<>();
	
	/** Simple counter for generating employee numbers. */
	private static int nextEmpNumber = 1;
  
	/** Session context for the current user (used by helpers like changeMyPassword). */
    @Inject
    private CurrentUser currentUser;

    /**
     * Container lifecycle hook.
     *
     * <p>Seeds the repository with a default admin and a sample user,
     * along with their credentials.</p>
     */
	@PostConstruct
	public void startup(){
        Admin admin = new Admin();
        admin.setUserName("admin");
        admin.setEmpNumber(0);
        admin.setName("System");
        employees.add(admin);

        Credentials adminCred = new Credentials();
        adminCred.setUserName("admin");
        adminCred.setPassword("admin123");
        credentials.add(adminCred);
		
		User user = new User();
		user.setUserName("user");
		user.setEmpNumber(1);
		user.setName("User");
		employees.add(user);
		
		Credentials userCred = new Credentials();
        userCred.setUserName("user");
        userCred.setPassword("password");
        credentials.add(userCred);
		
	}
	
	
	/**
     * employees getter.
     * @return the ArrayList of users.
     */
    @Override
    public List<Employee> getEmployees(){return new ArrayList<>(employees);}

    /**
     * Returns employee with a given name.
     * @param name the name field of the employee
     * @return the employees.
     */
    @Override
    public Employee getEmployee(String name){
        for (Employee e : employees) {
            if (name.equalsIgnoreCase(e.getUserName())) {
                return e;
            }
        }
        return null;
	  }

    /**
     * Return map of valid passwords for userNames.
     * @return the Map containing the valid (userName, password) combinations.
     */
    public Map<String, String> getLoginCombos(){
        Map<String, String> map = new HashMap<>();
        for (Credentials c : credentials) {
            if (c.getUserName() != null && c.getPassword() != null) {
                map.put(c.getUserName(), c.getPassword());
            }
        }
        return map;
    }

    /**
     * getter for currentEmployee property.  
     * @return the current user.
     */
    public Employee getCurrentEmployee(){
      return currentUser.getEmployee();
    }

    /**
     * Assumes single administrator and returns the employee object
     * of that administrator.
     * @return the administrator user object.
     */
    public Employee getAdministrator(){
      return employees.getFirst();
    }

    /**
     * Verifies that the loginID and password is a valid combination.
     *
     * @param credential (userName, Password) pair
     * @return true if it is, false if it is not.
     */
    public boolean verifyUser(Credentials credential){
        for (Credentials c : credentials) {
            if (credential.getUserName().equalsIgnoreCase(c.getUserName())
                    && credential.getPassword().equals(c.getPassword())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logs the user out of the system.
     *
     * @param employee the user to logout.
     * @return a String representing the login page.
     */
    public String logout(Employee employee){
        if (employee == null) {
            return "login";
        }


        if (currentUser != null
                && employee.getUserName() != null
                && employee.getUserName().equalsIgnoreCase(currentUser.getEmployee().getUserName())) {
            currentUser = null;
        }

        return "login";
    }

    /**
     * Deletes the specified user from the collection of Users.
     *
     * @param emp the user to delete.
     */
    @Override
    public void deleteEmployee(Employee emp){
        //ensure that the initial admin is never deleted
        if ("admin".equalsIgnoreCase(emp.getUserName())) {
            return;
        }

        employees.removeIf(e -> e.getEmpNumber() == emp.getEmpNumber());
        credentials.removeIf(e -> e.getUserName().equalsIgnoreCase(emp.getUserName()));
    }

    /**
     * Adds a new Employee to the collection of Employees.
     * @param emp the employee to add to the collection
     */
    @Override
    public void addEmployee(Employee emp){

        for (Employee e : employees) {
            if (e.getUserName() != null
                    && e.getUserName().equalsIgnoreCase(emp.getUserName())) {
                throw new IllegalStateException("Username already exists: " + emp.getUserName());
            }
			
			if (emp.getEmpNumber() != 0 && e.getEmpNumber() == emp.getEmpNumber()) {
				throw new IllegalStateException("Employee number already exists: " + emp.getEmpNumber());
			}
        }
		

        Credentials cred = new Credentials();
        cred.setUserName(emp.getUserName());
        cred.setPassword("password");

        employees.add(emp);
        credentials.add(cred);
    }

    /**
     * Changes the password for the given username (case-insensitive).
     *
     * @param userName username whose password will be updated
     * @param newPassword new password value (no policy enforced here)
     */
    public void changePassword(String userName, String newPassword){
        for (Credentials c : credentials) {
            if (userName.equalsIgnoreCase(c.getUserName())) {
                c.setPassword(newPassword);
                return;
            }
        }
    }

    /**
     * Changes the password of the currently logged-in user.
     *
     * @param newPassword new password for the current user
     */
    public void changeMyPassword(String newPassword){
        changePassword(currentUser.getEmployee().getUserName(), newPassword);
    }

    /**
     * Computes the next available employee number.
     *
     * <p>Finds the max existing emp number and adds 1. If none exist, returns 1.</p>
     *
     * @return next integer employee number
     */
	public int nextEmpNumber(){
		return employees.stream().mapToInt(Employee::getEmpNumber).max().orElse(0) + 1;
	}

}