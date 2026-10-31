
## Run tests fast
```shell
   mvn -o -DskipFrontend verify
```

## Sources
* Use plantuml in documents and then use https://plantuml.com/ to get the images

## Deployment
* **Start here to deploy to production** (ordered sequence: accounts and keys, secrets, DNS, deploy, verify, rollback): [deployment/provisioning-checklist.md#start-here-first-production-deploy](deployment/provisioning-checklist.md#start-here-first-production-deploy)
* Provisioning and go-live checklist (PROD and UAT): [deployment/provisioning-checklist.md](deployment/provisioning-checklist.md)
* Secrets and boot checks: [deployment/secrets-reference.md](deployment/secrets-reference.md); server walkthrough: [deployment/first-time-setup.md](deployment/first-time-setup.md); run it locally: [deployment/local/deployment.md](deployment/local/deployment.md) (or run `deployment/local/setup.sh`)
