## GET http://localhost:46485/echo?user=alice&action=view → 200 OK

### Response Headers
```
content-length: 64
content-type: application/json
date: Tue, 04 Aug 2026 22:12:02 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "user=alice&action=view"
}
```

### cURL
```bash
curl 'http://localhost:46485/echo?user=alice&action=view'
```
