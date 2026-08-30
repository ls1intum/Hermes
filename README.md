<div align="center">
    <h1 align="center">Artemis-Notifications</h1>
</div>

Notification Relay for Artemis Push Notifications.  
Allows secure and private push notifications from [Artemis](https://github.com/ls1intum/Artemis) to the mobile apps for [iOS](https://github.com/ls1intum/artemis-ios) and [Android](https://github.com/ls1intum/artemis-android).

![](hermes-system-decomposition.png)

### How to run:
1. Replace placeholder values `<...>` in Docker-Compose file
2. Adjust port if necessary
3. Run `docker-compose up`

### Further Information on Dockerfile

To run the services as an APNS relay the following Environment Variables are required (token-based authentication, as described [here](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns)):
- APNS_TOKEN_KEY_PATH: String - Path to the APNs signing key (.p8 file) inside the container
- APNS_TEAM_ID: String - The Apple Developer Team ID that owns the key
- APNS_KEY_ID: String - The Key ID of the APNs signing key
- APNS_PROD_ENVIRONMENT: Bool - True if it should use the Production APNS Server (Default false)
Furthermore the <APNS_Key>.p8 needs to be mounted into the Docker under the above specified path.
The provided docker-compose.yml does this for you: it sets APNS_TOKEN_KEY_PATH itself and mounts the key from the host path given in APNS_KEY_PATH (see example.env).


To run the services as a Firebase relay the following Environment Variable is required:
- GOOGLE_APPLICATION_CREDENTIALS: String - Path to the firebase.json
Furthermore the Firebase.json needs to be mounted into the Docker under the above specified path.

To run both APNS and Firebase configure the Environment Variables for both.