-- CRM Sales Opportunities data warehouse (MySQL 8 / MariaDB 10.x)
-- Re-runnable: drops and recreates every table. Run automatically by ETL_Main.
CREATE DATABASE IF NOT EXISTS crm_warehouse CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE crm_warehouse;

DROP TABLE IF EXISTS sales_pipeline;
DROP TABLE IF EXISTS sales_teams;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS accounts;

CREATE TABLE accounts (
  account          VARCHAR(100)  NOT NULL,
  sector           VARCHAR(50)   NOT NULL,
  year_established SMALLINT      NULL,
  revenue          DECIMAL(10,2) NULL,
  employees        INT           NULL,
  office_location  VARCHAR(60)   NOT NULL,
  subsidiary_of    VARCHAR(100)  NULL,
  PRIMARY KEY (account),
  KEY idx_accounts_year (year_established),
  CONSTRAINT fk_accounts_parent FOREIGN KEY (subsidiary_of) REFERENCES accounts (account)
) ENGINE=InnoDB;

CREATE TABLE products (
  product     VARCHAR(50)   NOT NULL,
  series      VARCHAR(20)   NOT NULL,
  sales_price DECIMAL(10,2) NOT NULL,
  PRIMARY KEY (product)
) ENGINE=InnoDB;

CREATE TABLE sales_teams (
  sales_agent     VARCHAR(60) NOT NULL,
  manager         VARCHAR(60) NOT NULL,
  regional_office VARCHAR(30) NOT NULL,
  PRIMARY KEY (sales_agent)
) ENGINE=InnoDB;

CREATE TABLE sales_pipeline (
  opportunity_id VARCHAR(12)   NOT NULL,
  sales_agent    VARCHAR(60)   NOT NULL,
  product        VARCHAR(50)   NOT NULL,
  account        VARCHAR(100)  NULL,
  deal_stage     ENUM('Prospecting','Engaging','Won','Lost') NOT NULL,
  engage_date    DATE          NULL,
  close_date     DATE          NULL,
  close_value    DECIMAL(12,2) NULL,
  PRIMARY KEY (opportunity_id),
  KEY idx_pipe_stage (deal_stage),
  KEY idx_pipe_product (product),
  KEY idx_pipe_agent (sales_agent),
  KEY idx_pipe_account (account),
  CONSTRAINT fk_pipe_agent   FOREIGN KEY (sales_agent) REFERENCES sales_teams (sales_agent),
  CONSTRAINT fk_pipe_product FOREIGN KEY (product)     REFERENCES products (product),
  CONSTRAINT fk_pipe_account FOREIGN KEY (account)     REFERENCES accounts (account)
) ENGINE=InnoDB;
