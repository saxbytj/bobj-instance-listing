# BusinessObjects Instance Listing

## Introduction
This program uses the BusinessObjects Java SDK to create a listing of un-paused recurring Webi instances in repository.
The listing including details of recurrence, prompts, destinations and any event dependencies.

## Prerequisites
* BusinessObjects 4.2 SP7 Platform Java SDK (may be installed as part of Client Tools or Server installation)
* Java SDK >= 1.7 (recommend using the SAPJVM bundled with the BusinessObjects installation)
* Credentials for a logon valid in the BusinessObjects environment with sufficient permissions to view across whole repository (such as an account with the Administrator role)
* Access to CMS ports on Application-tier server

### Optional Pre-requisites
* If using HTTPS, any additional certificates needed to verify the certificate of the web-tier server (not required if using certs signed by a public CA)
* If using Active Directory, configuration files for Java to use Kerberos (krb5.ini & bscLogon.conf)
* If using TLS on CORBA within application-tier services, SSL certificate, key, passphrase and trust store with any additional certificates needed to verify the application-tier server certificate

## Running the program
* Program can be compiled to a JAR file and uploaded to BusinessObjects to run as a program. In this case command line parameters or additional java parameters are required to run.
* Program can also be run locally - in this case some java parameters and command line parameters as described below will be needed.

### Command Line Parameters

Parameter|Description
---------|-----------
CMSNAME|Name of the server hosting the CMS repository|
USERNAME|Username to authenticate against CMS repository and Web Services. This user must have sufficient permissions to read all resources in the repository.
PASSWORD|Password associated with the above username.
AUTHTYPE|Type of authentication to use when authenticating against the CMS repository.


### Running locally
If BusinessObjects has been installed to a custom path, amend build.gradle to specify the location of the SAP BusinessObjects Enterprise XI 4.0/java/lib folder within this location.

If running locally, some additional java parameters are needed to make the program work:
 ``` 
 -Dbusinessobjects.connectivity.directory=C:\Program Files (x86)\SAP BusinessObjects\SAP BusinessObjects Enterprise XI 4.0\dataAccess\connectionServer
 ```
 
#### For Active Directory Authentication
Active directory authentication requires a path for two Kerberos configuration files to be specified. (Replace folder path with the location these are saved.)
```
-Djava.security.auth.login.config=C:\Program Files (x86)\SAP BusinessObjects\bscLogin.conf
-Djava.security.krb5.conf=C:\Program Files (x86)\SAP BusinessObjects\krb5.ini
```
##### Sample krb5.ini
```ini
[libdefaults]
  default_realm = MY.DOMAIN.ORG
  dns_lookup_kdc = true
  dns_lookup_realm = true
  default_tgs_enctypes = rc4-hmac,aes256-cts-hmac-sha1-96,aes128-cts-hmac-sha1-96
  default_tkt_enctypes = rc4-hmac,aes256-cts-hmac-sha1-96,aes128-cts-hmac-sha1-96
  udp_preference_limit = 1
[realms]
RAC.COM.AU = {
  default_domain = MY.DOMAIN.ORG
}
```
##### Sample bscLogin.conf
```java
com.businessobjects.security.jgss.initiate { 
com.sun.security.auth.module.Krb5LoginModule required debug=false;
};
```

#### CORBA SSL/TLS
Note that additional command line parameters will be required if using TLS on CORBA connections. See [BusinessObjects Administrator Guid](https://help.sap.com/doc/ec7df5236fdb101497906a7cb0e91070/4.2.7/en-US/sbo42sp6_bip_admin_en.pdf) for more details.
