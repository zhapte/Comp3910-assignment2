package com.corejsf;

import ca.bcit.infosys.employee.Credentials;
import ca.bcit.infosys.employee.Employee;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AuthServiceDB {
    @Inject private CredentialsDao creds;
    @Inject private EmployeeDao employees;
    
    public Employee authenticate(Credentials c) {
        try {
            if(!creds.verify(c)) return null;
            return employees.findByUserName(c.getUserName());
        } catch (Exception e) {
            return null;
        }
    }
    
    public void changePassword(String userName, String newPw) {
        try {
            creds.changePassword(userName, newPw);
        } catch (Exception ignore) {}
    }
    
    public boolean isAdmin(Employee e) {
        return e != null && (e instanceof Admin);
    }
    

}
