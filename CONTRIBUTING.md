# Contributing

When contributing to this repository, please first discuss the change you wish to make via issue,
email, or any other method with the owners of this repository before making a change.

Please note we have a code of conduct, please follow it in all your interactions with the project.

## Pull Request Process

1. Ensure any installation or build dependencies are removed before the end of the layer when doing a
   build.
2. Update the README.md with details of changes to the interface, this includes new environment
   variables, exposed ports, useful file locations and container parameters.
3. Increase the version numbers in any example files and the README.md to the new version that this
   Pull Request would represent. The versioning scheme we use is [SemVer](http://semver.org/).
4. You may merge the Pull Request in once you have the sign-off of two other developers, or if you
   do not have permission to do that, you may request the second reviewer to merge it for you.

## Guides

The following guides illustrate how to use some features concretely:

* [Caching Data with Spring](https://spring.io/guides/gs/caching/)
* [Messaging with Redis](https://spring.io/guides/gs/messaging-redis/)
* [Caffeine cache wiki](https://github.com/ben-manes/caffeine/wiki)
* [Resilience4J CircuitBreaker docs](https://resilience4j.readme.io/docs/circuitbreaker)

## Reference Documentation

For further reference, please consider the following sections:

### Build tools

The command typically used to build the project is:

```shell
./gradlew clean spotlessApply build
```

* [Official Gradle documentation](https://docs.gradle.org)

### Spring

* [Spring Boot Gradle Plugin Reference Guide](https://docs.spring.io/spring-boot/docs/2.5.3/gradle-plugin/reference/html/)
* [Spring cache abstraction](https://docs.spring.io/spring-boot/docs/2.5.3/reference/htmlsingle/#boot-features-caching)
* [Spring Data Redis (Access+Driver)](https://docs.spring.io/spring-boot/docs/2.5.3/reference/htmlsingle/#boot-features-redis)
* [Spring Configuration Processor](https://docs.spring.io/spring-boot/docs/2.5.3/reference/htmlsingle/#configuration-metadata-annotation-processor)

### Third-party libraries

* [Caffeine](https://github.com/ben-manes/caffeine)
* [Resilience4J](https://resilience4j.readme.io/docs/getting-started)

### Additional test libraries

* [Testcontainers](https://www.testcontainers.org/)

### Setting up test environment

- You need typical Spring Boot project for testing with Spring Cache / Spring Web for invoking something to trigger the cache.
- Spin up local Redis instance by using
```shell
docker run -d --name redis -p 6379:6379 redis:7.2.4-alpine
```
- Spin up Redis Insight dashboard to observe Redis state by using:
```shell
docker run -d --name redisinsight -p 5540:5540 redis/redisinsight:latest -v redisinsight:/data
```
- Redis Insight woiuld be typically available at http://localhost:5540, your Redis URL to connect would typically have IP of your Docker Machine: look into Redis container network setting by running `docker inspect redis` (typically IP would look like `172.17.0.2` or similar).

## Code of Conduct

### Our Pledge

In the interest of fostering an open and welcoming environment, we as
contributors and maintainers pledge to make participation in our project and
our community a harassment-free experience for everyone, regardless of age, body
size, disability, ethnicity, gender identity and expression, level of experience,
nationality, personal appearance, race, religion, or sexual identity and
orientation.

### Our Standards

Examples of behavior that contributes to creating a positive environment
include:

* Using welcoming and inclusive language
* Being respectful of differing viewpoints and experiences
* Gracefully accepting constructive criticism
* Focusing on what is best for the community
* Showing empathy towards other community members

Examples of unacceptable behavior by participants include:

* The use of sexualized language or imagery and unwelcome sexual attention or
  advances
* Trolling, insulting/derogatory comments, and personal or political attacks
* Public or private harassment
* Publishing others' private information, such as a physical or electronic
  address, without explicit permission
* Another conduct that could reasonably be considered inappropriate in a
  professional setting

### Our Responsibilities

Project maintainers are responsible for clarifying the standards of acceptable
behavior and are expected to take appropriate and fair corrective action in
response to any instances of unacceptable behavior.

Project maintainers have the right and responsibility to remove, edit, or
reject comments, commits, code, wiki edits, issues, and other contributions
that are not aligned to this Code of Conduct, or to ban temporarily or
permanently any contributor for other behaviors that they deem inappropriate,
threatening, offensive, or harmful.

### Scope

This Code of Conduct applies both within project spaces and in public spaces
when an individual is representing the project or its community. Examples of
representing a project or community include using an official project e-mail
address, posting via an official social media account, or acting as an appointed
representative at an online or offline event. Representation of a project may be
further defined and clarified by project maintainers.

### Enforcement

Instances of abusive, harassing, or otherwise unacceptable behavior may be
reported by contacting the project team at [INSERT EMAIL ADDRESS]. All
complaints will be reviewed and investigated and will result in a response 
deemed necessary and appropriate to the circumstances. The project team is
obligated to maintain confidentiality with regard to the reporter of an incident.
Further details of specific enforcement policies may be posted separately.

Project maintainers who do not follow or enforce the Code of Conduct in good
faith may face temporary or permanent repercussions as determined by other
members of the project's leadership.

### Attribution

This Code of Conduct is adapted from the [Contributor Covenant][homepage], version 1.4,
available at [http://contributor-covenant.org/version/1/4][version]

[homepage]: http://contributor-covenant.org
[version]: http://contributor-covenant.org/version/1/4/
