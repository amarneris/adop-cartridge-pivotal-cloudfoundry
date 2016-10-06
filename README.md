
# Overview

A Cartridge is a set of resources that are loaded into the Platform for a particular project. They may contain anything from a simple reference implementation for a technology to a set of best practice examples for building, deploying, and managing a technology stack that can be used by a project. 
In our case the cartridge is used to deploy a sample application (i.e. PetClinic) in Pivotal's Cloud Foundry (CF) platform. During the pipeline execution there are 2 deployments that are being done in 2 different CF spaces, deployment and production. The deployment one is using one instance with 1GB of memory and the second one 2 instances with 512MB each.

This cartridge consists of source code repositories and jenkins jobs.

## Source code repositories

Cartridge loads the source code repositories

* [spring-petclinic](https://github.com/spring-projects/spring-petclinic.git)


## Jenkins Jobs

This cartridge generates the jenkins jobs and pipeline views to -

* Build the source code from sprint petclinic repository.
* Running unit tests on the compiled code.
* Running sonar analysis on the code.
* Deploy to Pivotal Cloud Foundry environment (development space) using 1 instance.
* Run regression tests on deployed petclinic application.
* Run performance tests on deployed petclinic application.
* Run high availability test on deployed petclinic application (dev space).
* Deploy to Pivotal Cloud Foundry environment (production space) using 2 instances.
* Run high availability test on deployed petclinic application (prod space).



# License
Please view [license information](LICENSE.md) for the software contained on this image.

## Documentation
Documentation will be captured within this README.md and this repository's Wiki.

## Limitations
Pivotal 's Trial account provides memory usage maximum value of 2GB in total. This causes a few errors when we try to use the application in production space since we can assign only 512MB in each of the instances. Moreover this memory limitation seems to affect Jmeter report in application performance job. 
 



