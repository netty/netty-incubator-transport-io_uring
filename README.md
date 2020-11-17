# Netty io_uring

The new io_uring interface added to the Linux Kernel 5.1 is a high I/O performance scalable interface for fully asynchronous Linux syscalls

## Requirements:

- x86-64 processor
- at least 5.9
- to run the tests, you have to increase memlock(default 64K)


See [our wiki page](https://netty.io/wiki/native-transports.html).

## How to include the dependency

To include the dependency you need to ensure you also specify the right classifier. At the moment we only support linux
 x86_64 but this may change. 
 
As an example this is how you would include the dependency in maven:
```
<dependency>
    <groupId>io.netty.incubator</groupId>
    <artifactId>netty-incubator-transport-native-io_uring</artifactId>
    <version>0.0.1.Final</version>
    <classifier>linux-x86_64</classifier>
</dependency>
```
