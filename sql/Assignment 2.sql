CREATE DATABASE IF NOT EXISTS timesheets
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE timesheets;


CREATE TABLE employees (
  employee_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name            VARCHAR(120)    NOT NULL,
  emp_number      INT             NOT NULL,           
  user_name       VARCHAR(80)     NOT NULL,            
  role            ENUM('ADMIN','USER') NOT NULL DEFAULT 'USER',
  created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (employee_id),
  UNIQUE KEY uq_emp_emp_number (emp_number),
  UNIQUE KEY uq_emp_user_name  (user_name)
) 


CREATE TABLE credentials (
  employee_id     BIGINT UNSIGNED NOT NULL,
  password_hash   VARCHAR(255)    NOT NULL,           
  last_changed    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (employee_id),
  CONSTRAINT fk_cred_employee
    FOREIGN KEY (employee_id)
    REFERENCES employees(employee_id)
    ON DELETE CASCADE
    ON UPDATE CASCADE
) 


CREATE TABLE timesheets (
  timesheet_id    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  employee_id     BIGINT UNSIGNED NOT NULL,
  end_date        DATE            NOT NULL,           
  overtime_deci   INT             NOT NULL DEFAULT 0,  
  flextime_deci   INT             NOT NULL DEFAULT 0, 
  created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (timesheet_id),
  CONSTRAINT fk_ts_employee
    FOREIGN KEY (employee_id)
    REFERENCES employees(employee_id)
    ON DELETE CASCADE
    ON UPDATE CASCADE,
  UNIQUE KEY uq_ts_emp_week (employee_id, end_date),
  KEY idx_ts_emp_date (employee_id, end_date DESC)
) 


CREATE TABLE timesheet_rows (
  row_id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  timesheet_id    BIGINT UNSIGNED NOT NULL,
  line_no         INT             NOT NULL,           
  project_id      INT             NOT NULL DEFAULT 0,
  work_package_id VARCHAR(64)     NOT NULL DEFAULT '',
  packed_hours    BIGINT UNSIGNED NOT NULL DEFAULT 0,  
  notes           VARCHAR(512)    NULL,
  PRIMARY KEY (row_id),
  CONSTRAINT fk_tsr_timesheet
    FOREIGN KEY (timesheet_id)
    REFERENCES timesheets(timesheet_id)
    ON DELETE CASCADE
    ON UPDATE CASCADE,
  KEY idx_tsr_ts (timesheet_id, line_no)
)


INSERT INTO employees (name, emp_number, user_name, role)
VALUES ('System Admin', 0, 'admin', 'ADMIN');