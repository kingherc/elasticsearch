---
mapped_pages:
  - https://www.elastic.co/guide/en/elasticsearch/reference/current/rrf.html
applies_to:
  stack: all
---

# Reciprocal rank fusion [rrf]

[Reciprocal rank fusion (RRF)](https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf) is a method for combining multiple result sets with different relevance indicators into a single result set. RRF requires no tuning, and the different relevance indicators do not have to be related to each other to achieve high-quality results.

RRF uses the following formula to determine the score for ranking each document:

```python
score = 0.0
for q in queries:
    if d in result(q):
        score += 1.0 / ( k + rank( result(q), d ) )
return score

# where
# k is a ranking constant
# q is a query in the set of queries
# d is a document in the result set of q
# result(q) is the result set of q
# rank( result(q), d ) is d's rank within the result(q) starting from 1
```

## Reciprocal rank fusion API [rrf-api]

::::{admonition} New API reference
For the most up-to-date API details, refer to [Search APIs](https://www.elastic.co/docs/api/doc/elasticsearch/group/endpoint-search).

::::


You can use RRF as part of a [search](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-search) to combine and rank documents using separate sets of top documents (result sets) from a combination of [child retrievers](/reference/elasticsearch/rest-apis/retrievers.md) using an [RRF retriever](/reference/elasticsearch/rest-apis/retrievers/rrf-retriever.md). A minimum of **two** child retrievers is required for ranking.

An RRF retriever is an optional object defined as part of a search request’s [retriever parameter](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-search#request-body-retriever). The RRF retriever object contains the following parameters:

`retrievers`
:   (Required, array of retriever objects)

    A list of child retrievers to specify which sets of returned top documents will have the RRF formula applied to them. Each child retriever carries an equal weight as part of the RRF formula. Two or more child retrievers are required.


`rank_constant`
:   (Optional, integer)

    This value determines how much influence documents in individual result sets per query have over the final ranked result set. A higher value indicates that lower ranked documents have more influence. This value must be greater than or equal to `1`. Defaults to `60`.


`rank_window_size`
:   (Optional, integer)

    This value determines the size of the individual result sets per query. A higher value will improve result relevance at the cost of performance. The final ranked result set is pruned down to the search request’s [size](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-search#search-size-param). `rank_window_size` must be greater than or equal to `size` and greater than or equal to `1`. Defaults to the `size` parameter.


An example request using RRF:

```console
GET example-index/_search
{
    "retriever": {
        "rrf": { <3>
            "retrievers": [
                {
                    "standard": { <2>
                        "query": {
                            "term": {
                                "text": "shoes"
                            }
                        }
                    }
                },
                {
                    "knn": { <1>
                        "field": "vector",
                        "query_vector": [1.25, 2, 3.5],
                        "k": 50,
                        "num_candidates": 100
                    }
                }
            ],
            "rank_window_size": 50,
            "rank_constant": 20
        }
    }
}
```

In the above example, we execute the `knn` and `standard` retrievers independently of each other. Then we use the `rrf` retriever to combine the results.

1. First, we execute the kNN search specified by the `knn` retriever to get its global top 50 results.
2. Second, we execute the query specified by the `standard` retriever to get its global top 50 results.
3. Then, on a coordinating node, we combine the kNN search top documents with the query top documents and rank them based on the RRF formula using parameters from the `rrf` retriever to get the combined top documents using the default `size` of `10`.


Note that if `k` from a knn search is larger than `rank_window_size`, the results are truncated to `rank_window_size`. If `k` is smaller than `rank_window_size`, the results are `k` size.


## Reciprocal rank fusion supported features [rrf-supported-features]

The `rrf` retriever supports:

* [aggregations](/reference/aggregations/index.md)
* [from](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-search#search-from-param)
* [suggesters](/reference/elasticsearch/rest-apis/search-suggesters.md)
* [highlighting](/reference/elasticsearch/rest-apis/highlighting.md)
* [collapse](/reference/elasticsearch/rest-apis/collapse-search-results.md)
* [profiling](/reference/elasticsearch/rest-apis/search-profile.md)

The `rrf` retriever does not currently support:

* [scroll](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-search#search-api-scroll-query-param)
* [sort](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-search#search-sort-param)
* [rescore](/reference/elasticsearch/rest-apis/filter-search-results.md#rescore)

Using unsupported features as part of a search with an `rrf` retriever results in an exception.

::::{important}
It is best to avoid providing a [point in time](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-search#search-api-pit) as part of the request, as RRF creates one internally that is shared by all sub-retrievers to ensure consistent results.
::::



## Reciprocal rank fusion using multiple standard retrievers [rrf-using-multiple-standard-retrievers]

The `rrf` retriever provides a way to combine and rank multiple `standard` retrievers. A primary use case is combining top documents from a traditional BM25 query and an [ELSER](docs-content://solutions/search/semantic-search/semantic-search-elser-ingest-pipelines.md) query to achieve improved relevance.

An example request using RRF with multiple standard retrievers:

```console
GET example-index/_search
{
    "retriever": {
        "rrf": { <3>
            "retrievers": [
                {
                    "standard": { <1>
                        "query": {
                            "term": {
                                "text": "blue shoes sale"
                            }
                        }
                    }
                },
                {
                    "standard": { <2>
                        "query": {
                            "sparse_vector":{
                                "field": "ml.tokens",
                                "inference_id": "my_elser_model",
                                "query": "What blue shoes are on sale?"
                            }
                        }
                    }
                }
            ],
            "rank_window_size": 50,
            "rank_constant": 20
        }
    }
}
```

In the above example, we execute each of the two `standard` retrievers independently of each other. Then we use the `rrf` retriever to combine the results.

1. First we run the `standard` retriever specifying a term query for `blue shoes sales` using the standard BM25 scoring algorithm.
2. Next we run the `standard` retriever specifying a sparse_vector query for `What blue shoes are on sale?` using our [ELSER](docs-content://solutions/search/semantic-search/semantic-search-elser-ingest-pipelines.md) scoring algorithm.
3. The `rrf` retriever allows us to combine the two top documents sets generated by completely independent scoring algorithms with equal weighting.


Not only does this remove the need to figure out what the appropriate weighting is using linear combination, but RRF is also shown to give improved relevance over either query individually.


## Reciprocal rank fusion using sub searches [rrf-using-sub-searches]

RRF using sub searches is no longer supported. Use the [retriever API](/reference/elasticsearch/rest-apis/retrievers.md) instead. See [using multiple standard retrievers](#rrf-using-multiple-standard-retrievers) for an example.


## Reciprocal rank fusion full example [rrf-full-example]

We begin by creating a mapping for an index with a text field, a vector field, and an integer field along with indexing several documents. For this example we are going to use a vector with only a single dimension to make the ranking easier to explain.

```console
PUT example-index
{
    "mappings": {
        "properties": {
            "text" : {
                "type" : "text"
            },
            "vector": {
                "type": "dense_vector",
                "dims": 1,
                "index": true,
                "similarity": "l2_norm",
                 "index_options": {
                     "type": "hnsw"
                 }
            },
            "integer" : {
                "type" : "integer"
            }
        }
    }
}

PUT example-index/_doc/1
{
    "text" : "rrf",
    "vector" : [5],
    "integer": 1
}

PUT example-index/_doc/2
{
    "text" : "rrf rrf",
    "vector" : [4],
    "integer": 2
}

PUT example-index/_doc/3
{
    "text" : "rrf rrf rrf",
    "vector" : [3],
    "integer": 1
}

PUT example-index/_doc/4
{
    "text" : "rrf rrf rrf rrf",
    "integer": 2
}

PUT example-index/_doc/5
{
    "vector" : [0],
    "integer": 1
}

POST example-index/_refresh
```

We now execute a search using an `rrf` retriever with a `standard` retriever specifying a BM25 query, a `knn` retriever specifying a kNN search, and a terms aggregation.

```console
GET example-index/_search
{
    "retriever": {
        "rrf": {
            "retrievers": [
                {
                    "standard": {
                        "query": {
                            "term": {
                                "text": "rrf"
                            }
                        }
                    }
                },
                {
                    "knn": {
                        "field": "vector",
                        "query_vector": [3],
                        "k": 5,
                        "num_candidates": 5
                    }
                }
            ],
            "rank_window_size": 5,
            "rank_constant": 1
        }
    },
    "size": 3,
    "aggs": {
        "int_count": {
            "terms": {
                "field": "integer"
            }
        }
    }
}
```

And we receive the response with ranked `hits` and the terms aggregation result. We have both the ranker’s `score` and the `_rank` option to show our top-ranked documents.

```console-response
{
    "took": ...,
    "timed_out" : false,
    "_shards" : {
        "total" : 1,
        "successful" : 1,
        "skipped" : 0,
        "failed" : 0
    },
    "hits" : {
        "total" : {
            "value" : 5,
            "relation" : "eq"
        },
        "max_score" : ...,
        "hits" : [
            {
                "_index" : "example-index",
                "_id" : "3",
                "_score" : 0.8333334,
                "_source" : {
                    "integer" : 1,
                    "vector" : [
                        3
                    ],
                    "text" : "rrf rrf rrf"
                }
            },
            {
                "_index" : "example-index",
                "_id" : "2",
                "_score" : 0.5833334,
                "_source" : {
                    "integer" : 2,
                    "vector" : [
                        4
                    ],
                    "text" : "rrf rrf"
                }
            },
            {
                "_index" : "example-index",
                "_id" : "4",
                "_score" : 0.5,
                "_source" : {
                    "integer" : 2,
                    "text" : "rrf rrf rrf rrf"
                }
            }
        ]
    },
    "aggregations" : {
        "int_count" : {
            "doc_count_error_upper_bound" : 0,
            "sum_other_doc_count" : 0,
            "buckets" : [
                {
                    "key" : 1,
                    "doc_count" : 3
                },
                {
                    "key" : 2,
                    "doc_count" : 2
                }
            ]
        }
    }
}
```

Let’s break down how these hits were ranked. We start by running the `standard` retriever specifying a query and the `knn` retriever specifying a kNN search separately to collect what their individual hits are.

First, we look at the hits for the query from the `standard` retriever.

```console-result
"hits" : [
    {
        "_index" : "example-index",
        "_id" : "4",
        "_score" : 0.16152832,              <1>
        "_source" : {
            "integer" : 2,
            "text" : "rrf rrf rrf rrf"
        }
    },
    {
        "_index" : "example-index",
        "_id" : "3",                        <2>
        "_score" : 0.15876243,
        "_source" : {
            "integer" : 1,
            "vector" : [3],
            "text" : "rrf rrf rrf"
        }
    },
    {
        "_index" : "example-index",
        "_id" : "2",                        <3>
        "_score" : 0.15350538,
        "_source" : {
            "integer" : 2,
            "vector" : [4],
            "text" : "rrf rrf"
        }
    },
    {
        "_index" : "example-index",
        "_id" : "1",                        <4>
        "_score" : 0.13963442,
        "_source" : {
            "integer" : 1,
            "vector" : [5],
            "text" : "rrf"
        }
    }
]
```

1. rank 1, `_id` 4
2. rank 2, `_id` 3
3. rank 3, `_id` 2
4. rank 4, `_id` 1


Note that our first hit doesn’t have a value for the `vector` field. Now, we look at the results for the kNN search from the `knn` retriever.

```console-result
"hits" : [
    {
        "_index" : "example-index",
        "_id" : "3",                   <1>
        "_score" : 1.0,
        "_source" : {
            "integer" : 1,
            "vector" : [3],
            "text" : "rrf rrf rrf"
        }
    },
    {
        "_index" : "example-index",
        "_id" : "2",                   <2>
        "_score" : 0.5,
        "_source" : {
            "integer" : 2,
            "vector" : [4],
            "text" : "rrf rrf"
        }
    },
    {
        "_index" : "example-index",
        "_id" : "1",                   <3>
        "_score" : 0.2,
        "_source" : {
            "integer" : 1,
            "vector" : [5],
            "text" : "rrf"
        }
    },
    {
        "_index" : "example-index",
        "_id" : "5",                   <4>
        "_score" : 0.1,
        "_source" : {
            "integer" : 1,
            "vector" : [0]
        }
    }
]
```

1. rank 1, `_id` 3
2. rank 2, `_id` 2
3. rank 3, `_id` 1
4. rank 4, `_id` 5


We can now take the two individually ranked result sets and apply the RRF formula to them using parameters from the `rrf` retriever to get our final ranking.

```python
# doc  | query     | knn       | score
_id: 1 = 1.0/(1+4) + 1.0/(1+3) = 0.4500
_id: 2 = 1.0/(1+3) + 1.0/(1+2) = 0.5833
_id: 3 = 1.0/(1+2) + 1.0/(1+1) = 0.8333
_id: 4 = 1.0/(1+1)             = 0.5000
_id: 5 =             1.0/(1+4) = 0.2000
```

We rank the documents based on the RRF formula with a `rank_window_size` of `5` truncating the bottom `2` docs in our RRF result set with a `size` of `3`. We end with `_id: 3` as `_rank: 1`, `_id: 2` as `_rank: 2`, and `_id: 4` as `_rank: 3`. This ranking matches the result set from the original RRF search as expected.


## Explain in RRF [_explain_in_rrf]

In addition to individual query scoring details, we can make use of the `explain=true` parameter to get information on how the RRF scores for each document were computed. Working with the example above, and by adding `explain=true` to the search request, we’d now have a response that looks like the following:

```js
{
    "hits":
    [
        {
            "_index": "example-index",
            "_id": "3",
            "_score": 0.8333334,
            "_explanation":
            {
                "value": 0.8333334,                                                                                                                                               <1>
                "description": "rrf score: [0.8333334] computed for initial ranks [2, 1] with rankConstant: [1] as sum of [1 / (rank + rankConstant)] for each query",            <2>
                "details":                                                                                                                                                        <3>
                [
                    {
                        "value": 2,                                                                                                                                               <4>
                        "description": "rrf score: [0.33333334], for rank [2] in query at index [0] computed as [1 / (2 + 1]), for matching query with score: ",
                        "details":                                                                                                                                                <5>
                        [
                            {
                                "value": 0.15876243,
                                "description": "weight(text:rrf in 0) [PerFieldSimilarity], result of:",
                                "details":
                                [
                                    ...
                                ]
                            }
                        ]
                    },
                    {
                        "value": 1,                                                                                                                                              <6>
                        "description": "rrf score: [0.5], for rank [1] in query at index [1] computed as [1 / (1 + 1]), for matching query with score: ",
                        "details":
                        [
                            {
                                "value": 1,
                                "description": "within top k documents",
                                "details":
                                []
                            }
                        ]
                    }
                ]
            }
        }
        ...
    ]
}
```

1. the final RRF score for document with `_id=3`
2. a description on how this score was computed based on the ranks of this document in each individual query
3. details on how the RRF score was computed for each of the queries
4. the `value` heres specifies the `rank` of this document in the specific query
5. standard `explain` output of the underlying query, describing matching terms and weights
6. the `value` heres specifies the `rank` of this document for the second (`knn`) query


In addition to the above, explain in RRF also supports [named queries](/reference/query-languages/query-dsl/query-dsl-bool-query.md#named-queries) using the `_name` parameter. Using named queries allows for easier and more intuitive understanding of the RRF score computation, especially when dealing with multiple queries. So, we would now have:

```js
GET example-index/_search
{
    "retriever": {
        "rrf": {
            "retrievers": [
                {
                    "standard": {
                        "query": {
                            "term": {
                                "text": "rrf"
                            }
                        }
                    }
                },
                {
                    "knn": {
                        "field": "vector",
                        "query_vector": [3],
                        "k": 5,
                        "num_candidates": 5,
                        "_name": "my_knn_query"                           <1>
                    }
                }
            ],
            "rank_window_size": 5,
            "rank_constant": 1
        }
    },
    "size": 3,
    "aggs": {
        "int_count": {
            "terms": {
                "field": "integer"
            }
        }
    }
}
```

1. Here we specify a `_name` for the `knn` retriever


The response would now include the named query in the explanation:

```js
{
    "hits":
    [
        {
            "_index": "example-index",
            "_id": "3",
            "_score": 0.8333334,
            "_explanation":
            {
                "value": 0.8333334,
                "description": "rrf score: [0.8333334] computed for initial ranks [2, 1] with rankConstant: [1] as sum of [1 / (rank + rankConstant)] for each query",
                "details":
                [
                    {
                        "value": 2,
                        "description": "rrf score: [0.33333334], for rank [2] in query at index [0] computed as [1 / (2 + 1]), for matching query with score: ",
                        "details":
                        [
                            ...
                        ]
                    },
                    {
                        "value": 1,
                        "description": "rrf score: [0.5], for rank [1] in query [my_knn_query] computed as [1 / (1 + 1]), for matching query with score: ",                      <1>
                        "details":
                        [
                           ...
                        ]
                    }
                ]
            }
        }
        ...
    ]
}
```

1. Instead of the anonymous `at index n` , we now have a reference to the named query `my_knn_query`.



## Pagination in RRF [_pagination_in_rrf]

When using `rrf` you can paginate through the results using the `from` parameter. As the final ranking is solely dependent on the original query ranks, to ensure consistency when paginating, we have to make sure that while `from` changes, the order of what we have already seen remains intact. To that end, we’re using a fixed `rank_window_size` as the whole available result set upon which we can paginate. This essentially means that if:

* `from + size` ≤ `rank_window_size` : we could get  `results[from: from+size]` documents back from the final `rrf` ranked result set
* `from + size` > `rank_window_size` : we would get 0 results back, as the request would fall outside the available `rank_window_size`-sized result set.

An important thing to note here is that since `rank_window_size` is all the results that we’ll get to see from the individual query components, pagination guarantees consistency, i.e. no documents are skipped or duplicated in multiple pages, iff `rank_window_size` remains the same. If `rank_window_size` changes, then the order of the results might change as well, even for the same ranks.

To illustrate all of the above, let’s consider the following simplified example where we have two queries, `queryA` and `queryB` and their ranked documents:

```python
     |  queryA   |  queryB    |
_id: |  1        |  5         |
_id: |  2        |  4         |
_id: |  3        |  3         |
_id: |  4        |  1         |
_id: |           |  2         |
```

For `rank_window_size=5` we would get to see all documents from both `queryA` and `queryB`. Assuming a `rank_constant=1`, the `rrf` scores would be:

```python
# doc   | queryA     | queryB       | score
_id: 1 =  1.0/(1+1)  + 1.0/(1+4)      = 0.7
_id: 2 =  1.0/(1+2)  + 1.0/(1+5)      = 0.5
_id: 3 =  1.0/(1+3)  + 1.0/(1+3)      = 0.5
_id: 4 =  1.0/(1+4)  + 1.0/(1+2)      = 0.533
_id: 5 =    0        + 1.0/(1+1)      = 0.5
```

So the final ranked result set would be [`1`, `4`, `2`, `3`, `5`] and we would paginate over that, since `rank_window_size == len(results)`. In this scenario, we would have:

* `from=0, size=2` would return documents [`1`, `4`] with ranks `[1, 2]`
* `from=2, size=2` would return documents [`2`, `3`] with ranks `[3, 4]`
* `from=4, size=2` would return document [`5`] with rank `[5]`
* `from=6, size=2` would return an empty result set as it there are no more results to iterate over

Now, if we had a `rank_window_size=2`, we would only get to see `[1, 2]` and `[5, 4]` documents for queries `queryA` and `queryB` respectively. Working out the math, we would see that the results would now be slightly different, because we would have no knowledge of the documents in positions `[3: end]` for either query.

```python
# doc   | queryA     | queryB         | score
_id: 1 =  1.0/(1+1)  + 0              = 0.5
_id: 2 =  1.0/(1+2)  + 0              = 0.33
_id: 4 =    0        + 1.0/(1+2)      = 0.33
_id: 5 =    0        + 1.0/(1+1)      = 0.5
```

The final ranked result set would be [`1`, `5`, `2`, `4`], and we would be able to paginate on the top `rank_window_size` results, i.e. [`1`, `5`]. So for the same params as above, we would now have:

* `from=0, size=2` would return [`1`, `5`] with ranks `[1, 2]`
* `from=2, size=2` would return an empty result set as it would fall outside the available `rank_window_size` results.


## Aggregations in RRF [_aggregations_in_rrf]

The `rrf` retriever supports aggregations from all specified sub-retrievers. Important notes about aggregations:

* They operate on the complete result set from all sub-retrievers
* They are not limited by the `rank_window_size` parameter
* They process the union of all matching documents

For example, consider the following document set:

```js
{
    "_id": 1, "termA": "foo",
    "_id": 2, "termA": "foo", "termB": "bar",
    "_id": 3, "termA": "aardvark", "termB": "bar",
    "_id": 4, "termA": "foo", "termB": "bar"
}
```

Perform a term aggregation on the `termA` field using an `rrf` retriever:

```js
{
    "retriever": {
        "rrf": {
            "retrievers": [
                {
                    "standard": {
                        "query": {
                            "term": {
                                "termB": "bar"
                            }
                        }
                    }
                },
                {
                    "standard": {
                        "query": {
                            "match_all": { }
                        }
                    }
                }
            ],
            "rank_window_size": 1
        }
    },
    "size": 1,
    "aggs": {
        "termA_agg": {
            "terms": {
                "field": "termA"
            }
        }
    }
}
```

The aggregation results will include **all** matching documents, regardless of `rank_window_size`.

```js
{
    "foo": 3,
    "aardvark": 1
}
```


## Highlighting in RRF [_highlighting_in_rrf]

Using the `rrf` retriever, you can add [highlight snippets](/reference/elasticsearch/rest-apis/highlighting.md) to show relevant text snippets in your search results. Highlighted snippets are computed based on the matching text queries defined on the sub-retrievers.

::::{important}
Highlighting on vector fields, using either the `knn` retriever or a `knn` query, is not supported.
::::


A more specific example of highlighting in RRF can also be found in the [retrievers examples](retrievers/retrievers-examples.md#retrievers-examples-highlighting-retriever-results) page.


## Inner hits in RRF [_inner_hits_in_rrf]

The `rrf` retriever supports [inner hits](/reference/elasticsearch/rest-apis/retrieve-inner-hits.md) functionality, allowing you to retrieve related nested or parent/child documents alongside your main search results. Inner hits can be specified as part of any nested sub-retriever and will be propagated to the top-level parent retriever. Note that the inner hit computation will take place only at end of `rrf` retriever’s evaluation on the top matching documents, and not as part of the query execution of the nested sub-retrievers.

::::{important}
When defining multiple `inner_hits` sections across sub-retrievers:

* Each `inner_hits` section must have a unique name
* Names must be unique across all sub-retrievers in the search request

::::



