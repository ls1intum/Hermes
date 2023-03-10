<div align="center">
    <h1 align="center">Artemis-Notifications</h1>
</div>

Notification Relay for Artemis Push Notifications.  
Allows secure and private push notifications from [Artemis](https://github.com/ls1intum/Artemis) to the mobile apps for [iOS](https://github.com/ls1intum/artemis-ios) and [Android](https://github.com/ls1intum/artemis-android).

### How to run:
1. Replace placeholder values `<...>` in Docker-Compose file
2. Adjust port if necessary
3. Run `docker-compose up`

### Further Information on Dockerfile

To run the services as an APNS relay the following Environment Variables are required:
- APNS_CERTIFICATE_PATH: String - Path to the APNs certificate .p12 file as described [here](https://developer.apple.com/documentation/usernotifications/setting_up_a_remote_notification_server/establishing_a_certificate-based_connection_to_apns)
- APNS_CERTIFICATE_PWD: String - The APNS certificate password
- APNS_PROD_ENVIRONMENT: Bool - True if it should use the Production APNS Server (Default false) 
Furthermore the <APNS_Key>.p8 needs to be mounted into the Docker under the above specified path.


To run the services as a Firebase relay the following Environment Variable is required:
- GOOGLE_APPLICATION_CREDENTIALS: String - Path to the firebase.json
Furthermore the Firebase.json needs to be mounted into the Docker under the above specified path.

To run both APNS and Firebase configure the Environment Variables for both.
