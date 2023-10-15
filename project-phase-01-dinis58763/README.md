[![Review Assignment Due Date](https://classroom.github.com/assets/deadline-readme-button-24ddc0f5d75046c5622901739e7c5dd533143b0c8e959d652212380cedb1ea36.svg)](https://classroom.github.com/a/QSCr0f1Y)
# Project Phase 1 <!-- omit in toc -->

## Project generation link <!-- omit in toc -->

Use the following link to generate this project repository for your group

**<https://classroom.github.com/a/QSCr0f1Y>**

## Table of contents <!-- omit in toc -->

- [Organisational details](#organisational-details)
- [Overall goals](#overall-goals)
  - [Implementation](#implementation)
  - [Report](#report)
  - [Constraints](#constraints)
- [Evaluation Criteria](#evaluation-criteria)
- [Submission details](#submission-details)
- [Additional context](#additional-context)
  - [Architectural overview](#architectural-overview)
    - [Basic implementation](#basic-implementation)
    - [Anti-entropy optimisation](#anti-entropy-optimisation)
    - [HyParView optimisation](#hyparview-optimisation)
  - [Programming environment (Babel)](#programming-environment-babel)
  - [HyParView](#hyparview)
  - [Cluster](#cluster)

## Organisational details

- Groups of **three**. These groups should be created in GitHub when accessing the link above.
- **Deadline: 23:59:59 2023-10-18 (Quarta-feira)**
- Cluster access will be provided in due course for trialling your system: **you must pre-register your groups by sending an e-mail to the course professors ([nmp@fct.unl.pt](mailto:nmp@fct.unl.pt) and [a.davidson@fct.unl.pt](mailto:a.davidson@fct.unl.pt)) with the subject "*ASD 23/24 GROUP REGISTRATION*"**.
  - You will get a reply from the professor (eventually) providing your usernames and passwords to access the cluster.
  - If you cannot form a group of three, smaller groups may be created, but this is highly discouraged due to the workload.
  - Anyone who is struggling to find a group to join should email the course professors **ASAP**.

## Overall goals

### Implementation

The project focuses on the study and implementation of reliable broadcast. To this end, the goals/steps of this first phase of the project are defined as follows:

1. Develop a distributed system (in Java, using [Babel](https://github.com/pfouto/babel-core), source code [provided](./src)) that implements a *reliable* broadcast algorithm (Lecture 1 and Lecture 2) using a gossip/epidemic protocol (Lecture 2) for deciding which peers to communicate with.
2. Add the *"anti-entropy"* optimisation (Lecture 2), to reduce the number of redundant messages that are sent throughout the system.
3. Implement the *[HyParView](https://asc.di.fct.unl.pt/~jleitao/pdf/dsn07-leitao.pdf)* protocol for allowing your reliable broadcast algorithm to request peers to communicate with more efficiently.

The base source code for your project should be developed in the `src/asd-project1-base` folder provided in the base [source code](./src/asd-project1-base/) folder.



### Report

Write a report, using the [latex template](./latex) provided, that details the following information.

1. The design of your system
2. The methodology that you used to design it
3. An analysis of how your system deals with transient failures of processes
4. A performance analysis of your system, relative to the original system as well as the impact of the anti-entropy and HyParView optimisations.

### Constraints

**It is important that these constraints are satisfied in your final delivery.**

- Your distributed system must contain 100+ processes
- The report must have a maximum of 8 pages, including figures and tables, but excluding bibliography.

## Evaluation Criteria

The project delivery includes both the code and a written report that should have the format of a short paper. The report must contain clear and readable pseudo-code for each of the implemented protocols, alongside a description of the intuition of these protocols. A correctness argument for protocol that was devised or adapted by students will be positively considered in grading the project. The written report should also provide information about all experimental work conducted by the students to evaluate their solution in practice (i.e., description of experiments, setup, parameters) as well as the results and a discussion of those results.

- The project will be evaluated by the correctness of the implemented solutions, its efficiency, and the quality of the implementations (in terms of code readability).
- The quality and clearness of the report of the project will have an impact the final grade. Students with a poorly written report run the risk of penalisation, based on the evaluation of the correctness the solutions employed.

This phase of the project will be graded in a scale from 1 to 20 with the following considerations:

- Groups that only implement a Reliable Broadcast algorithm (using an epidemic/Gossip model) and that experimentally evaluate those protocols with a single set of experimental parameters, will at most have a grade of $12$.
- Groups that implement both optimisations (Anti-Entropy and HyParView) and experimentally evaluate them can get up to $4$ additional points.
- Groups that consider *dynamic peer memberships*, and additionally conduct experimental evaluation of all implementations and optimisations using a combination of two different payload sizes for content stored and retrieved from the system and two different rates of requests issued by their test application, can get $4$ additional points.

## Submission details

The code and report that you submit should be pushed to this repository. You will then submit the **link to your repository** and the **commit ID** corresponding to the version of the project that you would like to have evaluated. Details for submission will be provided closer to the deadline.

## Additional context

### Architectural overview

#### Basic implementation

In the basic implementation, there will be an application (see the base code in the [src](./src/asd-project1-base/)) that will simply generate messages, and log which messages have been delivered by the system. This protocol will communicate with the Reliable Broadcast algorithm that you will implement, which will communicate directly with the wider network via a Gossip protocol.

The base code floods the entire system with messages, which is inefficient. You will first have to modify it to select a subset (`t`) of other processes to interact with.

```
______________________________________________________
|                                                     |
|                                                     |
|                     Application                     |
|                                                     |
|_____________________________________________________|
          |                               ^
          |                               |
      broadcast(m)                     deliver(m)
          |                               |
          v                               |
______________________________________________________
|                  Reliable Broadcast                 |
|                                                     |
|               // Send messages to gossip neighbours |----->
|                                                     |
|          // Receive messages from gossip neighbours |<-----
|_____________________________________________________|
```

#### Anti-entropy optimisation

Your anti-entropy module should communicate with the network before any message is send to other node, to know which message the remote peer is missing.
This *should* reduce the number of messages in the system, without contradicting the guarantees of the reliable broadcast.

```
______________________________________________________
|                                                     |
|                                                     |
|                     Application                     |
|                                                     |
|_____________________________________________________|
          |                               ^
          |                               |
      broadcast(m)                     deliver(m)
          |                               |
          v                               |
______________________________________________________        
|      Reliable Broadcast with Anti-entropy           |
|                                                     |
|    // Send messages to peer (based on anti-entropy) |----->
|                                                     |
|                      // Receive messages from peers |<-----
|_____________________________________________________|
         |                                ^
         |                                |
    Request_peer()                    Receive(peer)
         |                                |
         v                                |
______________________________________________________
|                 Membership algorithm                |
|                                                     |
|                // Communicate with network to learn |---->
|                // a neighbour process               |
|                                                     |<----
|_____________________________________________________|
```

#### HyParView optimisation

We discuss [below](#hyparview) how HyParView works to provide a more optimal overlay network for propagating messages. The advantage of using HyParView is that the membership algorithm selects peers to ensure a more efficient propagation of messages.

```
______________________________________________________
|                                                     |
|                                                     |
|                     Application                     |
|                                                     |
|_____________________________________________________|
          |                               ^
          |                               |
      broadcast(m)                     deliver(m)
          |                               |
          v                               |
______________________________________________________
|     Reliable Broadcast with Anti-Entropy            |
|                                                     |
|    // Send messages to peers learned from HyParView |----->
|                                                     |
|                      // Receive messages from peers |<-----
|_____________________________________________________|
         |                                ^
         |                                |
  Request_peers()                    Receive(peers)
         |                                |
         v                                |
______________________________________________________
|                                                     |
|                                                     |
|                     HyParView                       |
|                                                     |
|_____________________________________________________|
```

### Programming environment (Babel)

The students will develop their project using the Java language (version 11 minimum). Development will be conducted using a framework developed in the context of the NOVA LINCS laboratory written by Pedro Fouto, Pedro Ákos Costa, João Leitão, known as **Babel**.

The framework uses to the [Netty framework](https://netty.io) to support inter-process communication through sockets (although it was designed to hide this from the programmer). The framework will be discussed in the labs. An example application will be provided that is responsible for generating and logging messages in the system.

The javadoc of the framework can be found here: <https://asc.di.fct.unl.pt/~jleitao/babel/>. A more detailed description can be found in the slides provided in the [additional docs](./docs/babel-slides.pdf). Example code for running a broadcast algorithm is provided in the base [source code](./src/asd-project1-base/) folder.

The framework was specifically designed thinking about two complementary goals: $i)$ quick design and implementation of efficient distributed protocols; and $ii)$ teaching distributed algorithms in advanced courses. A significant effort was made to make it such that the code maps closely to protocols descriptions using (modern) pseudo-code. The goal is that you can focus on the key aspects of the protocols, their operation, and their correctness, and that you can easily implement and execute such protocols.

While this is the fourth year that Babel is being used in this course, and the current version has also been used to develop several research prototypes, the framework itself is still considered a prototype, and naturally some bugs can be found. Any problems that you encounter can be raised with the course professors.

### HyParView

[HyParView](https://asc.di.fct.unl.pt/~jleitao/pdf/dsn07-leitao.pdf), developed in 2007, is a fault-tolerant overlay network. HyParView is based on two distinct partial views of the system that are maintained for different purposes and using different mechanisms. A small partial-view (active view) that is used to ensure communication and cooperation between nodes, that is managed using a reactive strategy, where the contents of these views are only changed in reaction to an external event, such as a node failing or joining the system (or indirect consequences of these events). These views rely on TCP as an unreliable fault detector. A second and larger view (passive view) is used for fault-tolerance, as a source of quick replacements on the active view when this view is not complete. The fact that HyParView maintains a highly stable active view (and hence the overlay denoted by these views is also stable) offers some possibility to improve the communication pattern of nodes disseminating information.

### Cluster

The technical specification of the cluster as well as the documentation on how to use it (including changing your group password and making reservations) is online at: <https://cluster.di.fct.unl.pt>. You should read the documentation carefully. Once you have received your credentials you should be able to use it freely.
