package com.corejsf;

import jakarta.enterprise.context.Conversation;
import jakarta.enterprise.context.ConversationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.List;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;

/**
 * Managed bean that provides administrative functionality for managing employees.
 *
 * <p>Scope: {@link ConversationScoped} — persists across multiple requests
 * while the admin is managing users, and ends when navigation leaves the admin pages.</p>
 *
 * <p>This bean allows an administrator to view all employees, add new users or admins,
 * delete existing employees (with safeguards), and reset user passwords.</p>
 */
@Named("adminUserBean")
@ConversationScoped
public class AdminUserBean implements Serializable{
	
    /** Repository for managing employees and credentials. */
	@Inject
	private EmployeeRepo employees;
	
	/** CDI conversation used to maintain state during multi-step operations. */
	@Inject
	private Conversation conversation;
	
	/** Full name of the new employee being added. */
	private String name;
	
	/** Employee number for the new user (auto-incremented if zero). */
	private int empNumber;
	
	/** Username for login credentials. */
	private String userName;
	
	/** Whether the new employee should be created as an administrator. */
	private boolean admin;
	
	/** @return employee full name */
	public String getName() {
        return name;
    }

	/** @param name sets the employee’s full name */
    public void setName(String name) {
        this.name = name;
    }

    /** @return employee number */
    public int getEmpNumber() {
        return empNumber;
    }

    /** @param empNumber sets the employee number */
    public void setEmpNumber(int empNumber) {
        this.empNumber = empNumber;
    }

    /** @return username used for login */
    public String getUserName() {
        return userName;
    }

    /** @param userName sets the login username */
    public void setUserName(String userName) {
        this.userName = userName;
    }

    /** @return true if creating an admin account */
    public boolean isAdmin() {
        return admin;
    }

    /** @param admin sets whether this new employee will be an admin */
    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    /**
     * Starts a conversation if not already active.
     * <p>Ensures a consistent bean state across multiple page requests.</p>
     * <p>Also assigns a new employee number if none is set yet.</p>
     */
	public void start(){
		if (conversation.isTransient()) {
			conversation.begin();
        }
		if (empNumber == 0) {
            empNumber = employees.nextEmpNumber();
        }
	}
	
	/**
     * Ends the conversation if active.
     * <p>Typically called after an add/delete action or when leaving the admin view.</p>
     */
	public void end() {
        if (!conversation.isTransient()) {
            conversation.end();
        }
    }
	
	/**
     * Retrieves the list of all employees in the system.
     * <p>Begins a conversation before returning the list.</p>
     *
     * @return list of all {@link Employee} objects
     */
	public List<Employee> getEmployees(){
		start();
		return employees.getEmployees();
	}
	
	/**
     * Clears all form fields used when adding a new employee.
     */
	private void clearForm() {
        name = "";
        empNumber = 0;
        userName = "";
        admin = false;
    }
	
	/**
     * Adds a new employee or admin to the system.
     *
     * <p>Creates either a {@link User} or {@link Admin} object, validates fields,
     * and stores it in {@link EmployeeRepo}. Displays a success or error message
     * via {@link FacesContext}.</p>
     */
	public void addEmployee(){
		start();
		try{
			Employee e = admin ? new Admin() : new User();
			e.setName(name);
			e.setEmpNumber(empNumber);
			e.setUserName(userName);
			employees.addEmployee(e);
			
			FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO,
                        (admin ? "Admin " : "User ") + userName + " added.", null));
			
			clearForm();
			
		}catch (Exception ex) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", ex.getMessage()));
        }
	}
	
	/**
     * Deletes an employee from the system (except the default admin).
     *
     * <p>Displays success or warning messages via {@link FacesContext}.</p>
     *
     * @param emp the {@link Employee} to delete
     */
	public void deleteEmployee(Employee emp){
		start();
		
		if ("admin".equalsIgnoreCase(emp.getUserName())) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN,
                        "Cannot delete the seeded admin.", null));
            return;
        }
		
        employees.deleteEmployee(emp);
        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, emp.getUserName() + " deleted.", null));
	}
	
	/**
     * Returns a string label for the employee’s role.
     *
     * @param emp employee to evaluate
     * @return "Admin" if the employee is an {@link Admin}, otherwise "User"
     */
	public String getRole(Employee emp) {
			return (emp instanceof Admin) ? "Admin" : "User";
	}
	
	/**
     * Resets the specified employee’s password to the default ("password").
     *
     * @param emp the {@link Employee} whose password will be reset
     */
	public void resetPassword(Employee emp){
		employees.changePassword(emp.getUserName(), "password");
	}
	

}