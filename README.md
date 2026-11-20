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

Optionally, one relay can serve both TestFlight/App Store builds (production device tokens) and Xcode debug builds (sandbox device tokens):
- APNS_FALLBACK_ENVIRONMENT_ENABLED: Bool - If true, a notification that the APNS server selected by APNS_PROD_ENVIRONMENT rejects as `BadDeviceToken` is retried once on the other APNS server (Default false)

Leave it off for a relay that must only talk to the Production APNS Server. The fallback needs a signing key that Apple allows for both environments (Sandbox & Production). It never affects the health endpoint; if the key is not valid for the other environment, those tokens are simply rejected.

Furthermore the <APNS_Key>.p8 needs to be mounted into the Docker under the above specified path.
The provided docker-compose.yml does this for you: it sets APNS_TOKEN_KEY_PATH itself and mounts the key from the host path given in APNS_KEY_PATH (see example.env).


To run the services as a Firebase relay the following Environment Variable is required:
- GOOGLE_APPLICATION_CREDENTIALS: String - Path to the firebase.json
Furthermore the Firebase.json needs to be mounted into the Docker under the above specified path.

To run both APNS and Firebase configure the Environment Variables for both.

### Running as a non-root user (optional)

The image contains an unprivileged user `hermes` (uid/gid 10001) but starts as root by default, because the key files are usually only readable by root. To harden a deployment, make the mounted APNs signing key and Firebase credentials readable by uid 10001 **first**:

```bash
sudo chown 10001:10001 /opt/hermes/AuthKey_<apns_key_id>.p8 /opt/hermes/artemis.json
sudo chmod 400 /opt/hermes/AuthKey_<apns_key_id>.p8 /opt/hermes/artemis.json
```

then run the container with `user: "10001:10001"` (commented out in `docker-compose.yml`). If you switch before the files are readable, the relay starts but both providers are unavailable: the log shows `Permission denied`, `/api/health` reports `isApnsConnected` and `isFirebaseConnected` as `false`, and sends return 503. Note that the Docker `HEALTHCHECK` (`/api/push_notification/alive`) only proves that the process is up, so it stays healthy in that case; check `/api/health` instead.

### Image tags and rollback

Builds of `main` are published as `latest`, as `main` and, since the `sha-` tag was introduced, as an immutable `sha-<short commit>` tag. Deploy a `sha-...` tag to pin an exact build; to roll back, deploy the `sha-...` tag of the previous good build again. Only builds made after this tag was introduced have one.

### Optional APNs settings

All of these can also be set via environment variables (e.g. `apns.alert-priority` -> `APNS_ALERT_PRIORITY`); defaults are in `hermes/src/main/resources/application.properties`:
- `apns.topic` - the app's bundle identifier (default `de.tum.cit.ase.artemis`)
- `apns.expiration-days` - how long APNs retries delivery to an offline device (default 7)
- `apns.alert-priority` - delivery priority of visible notifications, `10` (immediately) or `5` (may be delayed to save power; default 5). Background notifications always use 5.

### Metrics

Queue depth and active workers per provider (`executor_queued_tasks`, `executor_active_threads`) as well as the number of requests answered with 503 because the queue was full (`hermes_send_rejected_total`) or the response timeout fired (`hermes_send_timeouts_total`) are exposed in Prometheus format at `/actuator/prometheus` on the **management port 8081**. That port is not published by `docker-compose.yml`, so it is not reachable through the public relay port; change it with `MANAGEMENT_SERVER_PORT` (the relay does not start if the port is already in use; this matters for host networking only), and scrape it from the Docker network (e.g. a Prometheus container in the same compose network) or publish it to localhost only (`127.0.0.1:8081:8081`).

### Request validation

`send_apns` and `send_firebase` answer `400 Bad Request` when `initializationVector`, `payloadCipherText` or `token` is missing or empty (`apiType` is not validated).