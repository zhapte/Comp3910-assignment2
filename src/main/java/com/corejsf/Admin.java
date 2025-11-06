package com.corejsf;

import java.io.Serializable;
import ca.bcit.infosys.timesheet.*;
import ca.bcit.infosys.employee.*;

/**
* Wrapper around the employee class so in the future we can modify it to give it more functions or data
*/
public class Admin extends User implements Serializable {
    public Admin() {
        super();
    }
}
