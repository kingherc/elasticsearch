% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

**Example**

```esql
FROM hosts
| WHERE CIDR_MATCH(ip1, "127.0.0.2/32", "127.0.0.3/32")
| KEEP card, host, ip0, ip1
```

| card:keyword | host:keyword | ip0:ip | ip1:ip |
| --- | --- | --- | --- |
| eth1 | beta | 127.0.0.1 | 127.0.0.2 |
| eth0 | gamma | fe80::cae2:65ff:fece:feb9 | 127.0.0.3 |


