# Pelagios API 2.0

This is the latest incarnation of the Pelagios API, developed as part of the 
[Pelagios 3 Project](http://pelagios-project.blogspot.co.uk). The new Pelagios API
is work in progress (which translates as "not everything works just yet").

__Our development instance is online at [http://pelagios.org/api-v3](http://pelagios.org/api-v3) - feel
free to give it a spin.__

## API Basics

Pelagios is a __community network__ with the goal to facilitate __better linking between online
resources documenting the past__, based on the __places they refer to__. The purpose of the __Pelagios
API__ is to make our network of links __browse-__ and __searchable__. The 'mental model' behind our API
is simple, and consists of only three types of entities:

* __Items__, such as archaeological artefacts, literary texts or photographs.
* __Places__ to which the items are related, e.g. places mentioned in texts or findspots of artefacts.
* __Datasets__ which contain the items, e.g. a particular museum collection or data corpus published by an institution.

Datasets as well as items can be __hierarchical__. E.g. the [Pelagios 3 dataset](http://pelagios.org/api-v3/pages/datasets/2a10228dff4c608b91a953efff8dafb3f5c433035b3f31e687eec0297d799824)
is sub-divided into a corpus of [Greek](http://pelagios.org/api-v3/pages/datasets/48ea51486cb33aae9e08501825a67fa0ba5770c5732742039e13a91ee75d5620)
and [Latin literary texts](http://pelagios.org/api-v3/pages/datasets/49d46d26fbde0f17cd09f16ff5561d930fd02775160c7ad1cba652ebbf3b2db8).
Likewise, an item such as Herodotus' _The Histories_ can be subdivided into individual _Books_.

## Response Format

The API returns responses in JSON format. [CORS](http://de.wikipedia.org/wiki/Cross-Origin_Resource_Sharing) and
[JSONP](http://en.wikipedia.org/wiki/JSONP) are both supported. Most responses are __paginated__, i.e. you will get back only one "page" of search results:

```json
{
  "total" : 112,
  "limit" : 20,
  "items" : [
  
    ...
  
  ]
}
```

You can traverse pages using an _offset_ and _limit_ (= page size) parameter. If _limit_ is omitted, it will
default to a page size of 20. Example:

Results 1 - 20:
[http://pelagios.org/api-v3/search?query=gold+AND+coin](http://pelagios.org/api-v3/search?query=gold+AND+coin&prettyprint=true)

Results 21 - 40:
[http://pelagios.org/api-v3/search?query=gold+AND+coin&offset=20](http://pelagios.org/api-v3/search?query=gold+AND+coin&offset=20&prettyprint=true)

## Pretty Printing

Per default, the API will return a compact, unformatted JSON response. You can force a more human-readable response by
appending a `prettyprint=true` parameter. Example:

[http://pelagios.org/api-v3/search?query=bronze&prettyprint=true](http://pelagios.org/api-v3/search?query=bronze&prettyprint=true)

## Searching the API

The main feature you'll probably want to use is __search__. You can currently search the API by __keyword query__, 
__entity type__, __dataset__, __places__ and __time interval__ (or any combination of those). The base URL for 
search is __http://pelagios.org/api-v3/search?__, followed by any combination of query parameters:

### query 

A keyword query. Returns exact matches only (i.e. no fuzzy search). Supports AND and OR operators, and trailing
asterisk for prefix queries. Examples:

[http://pelagios.org/api-v3/search?query=gold+AND+coin](http://pelagios.org/api-v3/search?query=gold+AND+coin&prettyprint=true)
[http://pelagios.org/api-v3/search?query=athen*](http://pelagios.org/api-v3/search?query=athen*&prettyprint=true)

### type

Restrict the results to `place`, `dataset` or `item`. Examples:
[http://pelagios.org/api-v3/search?query=bronze&type=place](http://pelagios.org/api-v3/search?query=bronze&type=place&prettyprint=true)

### dataset

Restrict results to one specific dataset. E.g. find everything for 'netherlands' in the [Following Hadrian](http://pelagios.org/api-v3/pages/datasets/ca22250344a3b20d3a79f33c39e703a7f2d9899bd3e3cf6057cd80530f0944e2)
photo collection:

[http://pelagios.org/api-v3/search?query=netherlands&dataset=ca22250344a3b20d3a79f33c39e703a7f2d9899bd3e3cf6057cd80530f0944e2](http://pelagios.org/api-v3/search?query=netherlands&dataset=ca22250344a3b20d3a79f33c39e703a7f2d9899bd3e3cf6057cd80530f0944e2&prettyprint=true)

### places

Restrict to one or more places. Places are identified by (a comma-separated list of) gazetteer URIs. (Search by coordinate is under development.) 
If more than one place is specified, they are logically combined to an AND query. That means the search will return items that reference __all__
of the places in the list. E.g. find everything that referes to both Rome and Syria:

[http://pelagios.org/api-v3/search?places=http:%2F%2Fpleiades.stoa.org%2Fplaces%2F981550,http:%2F%2Fpleiades.stoa.org%2Fplaces%2F423025](http://pelagios.org/api-v3/search?places=http:%2F%2Fpleiades.stoa.org%2Fplaces%2F981550,http:%2F%2Fpleiades.stoa.org%2Fplaces%2F423025&prettyprint=true)

### from, to

Restrict the results to a specific time interval. Both parameters take an integer number, which is interpreted as year. (Use negative
numbers for BC years.) If you are interested in a specific year, use the same value for `from` and `to`. 
Note: items in Pelagios that are __not dated will not appear in the results__. Examples:

[http://pelagios.org/api-v3/search?query=coin&from=-600&to=-500](http://pelagios.org/api-v3/search?query=coin&from=-600&to=-500&prettyprint=true)
[http://pelagios.org/api-v3/search?from=2014&to=2014][http://pelagios.org/api-v3/search?from=2014&to=2014&prettyprint=true]


## REST-style access methods

The API provides the following methods:

* __[/api-v3/datasets](http://pelagios.org/api-v3/datasets?prettyprint=true)__ List all datasets (paginated).
* __[/api-v3/datasets/:id](http://pelagios.org/api-v3/datasets/174524047516a97f0ba45d4af5e485dd?prettyprint=true)__  Get the dataset with the specified ID.
* __[/api-v3/datasets/:id/items](http://pelagios.org/api-v3/datasets/174524047516a97f0ba45d4af5e485dd/items?prettyprint=true)__ List all items contained in this dataset (paginated).
* __[/api-v3/datasets/:id/places](http://pelagios.org/api-v3/datasets/174524047516a97f0ba45d4af5e485dd/places?prettyprint=true)__ List all places contained in this dataset (paginated). Append 'verbose=false' as query parameter to receive a less verbose response, which has additional performance benefits (i.e. it will load faster than the full response)
* __[/api-v3/items/:id](http://pelagios.org/api-v3/items/bae00692d9a64e3e947bd5819102d01d?prettyprint=true)__ Get the item with the specified ID
* __[/api-v3/items/:id/items](http://pelagios.org/api-v3/items/2dbf2df9a073bd29710abdf3a4330996/items?prettyPrint=true)__ List sub-items to this item (paginated)
* __[/api-v3/items/:id/places](http://pelagios.org/api-v3/items/bae00692d9a64e3e947bd5819102d01d/places?prettyprint=true)__ List all places that are referenced by this item (paginated)
* __[/api-v3/items/:id/annotations](http://pelagios.org/api-v3/items/bae00692d9a64e3e947bd5819102d01d/annotations?prettyprint=true)__ List the raw annotations on this item (paginated)
* __[/api-v3/annotations/:id](http://pelagios.org/api-v3/annotations/39dae3ef-b40e-4535-99df-f02c5e507659)__ Get the annotation with the specified ID
* __[/api-v3/places/:uri](http://pelagios.org/api-v3/places/http%3A%2F%2Fpleiades.stoa.org%2Fplaces%2F658381?prettyprint=true)__ Get the place with the specified URI (be sure to URL-escape the URI!)

## HTML Views

The API will also provide 'human-readable output' - i.e. HTML pages with the same information that
appears in the JSON responses. This work is just getting started. See

[http://pelagios.org/api-v3/pages/datasets/42362e18923860b43ed4c048f16274f7](http://pelagios.org/api-v3/pages/datasets/42362e18923860b43ed4c048f16274f7)

for an example.

## License

The Pelagios API is licensed under the [GNU General Public License v3.0](http://www.gnu.org/licenses/gpl.html).
