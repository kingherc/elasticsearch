---
navigation_title: "Span not"
mapped_pages:
  - https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-span-not-query.html
---

# Span not query [query-dsl-span-not-query]


Removes matches which overlap with another span query or which are within x tokens before (controlled by the parameter `pre`) or y tokens after (controlled by the parameter `post`) another SpanQuery. Here is an example:

```console
GET /_search
{
  "query": {
    "span_not": {
      "include": {
        "span_term": { "field1": "hoya" }
      },
      "exclude": {
        "span_near": {
          "clauses": [
            { "span_term": { "field1": "la" } },
            { "span_term": { "field1": "hoya" } }
          ],
          "slop": 0,
          "in_order": true
        }
      }
    }
  }
}
```

The `include` and `exclude` clauses can be any span type query. The `include` clause is the span query whose matches are filtered, and the `exclude` clause is the span query whose matches must not overlap those returned.

In the above example all documents with the term hoya are filtered except the ones that have *la* preceding them.

Other top level options:

`pre`
:   If set the amount of tokens before the include span can’t have overlap with the exclude span. Defaults to 0.

`post`
:   If set the amount of tokens after the include span can’t have overlap with the exclude span. Defaults to 0.

`dist`
:   If set the amount of tokens from within the include span can’t have overlap with the exclude span. Equivalent of setting both `pre` and `post`.

