% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

### MV MAX
Converts a multivalued expression into a single valued column containing the maximum value.

```esql
ROW a=[3, 5, 1]
| EVAL max_a = MV_MAX(a)
```
