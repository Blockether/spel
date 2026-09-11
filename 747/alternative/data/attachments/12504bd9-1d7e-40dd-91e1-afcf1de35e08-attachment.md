## GET http://localhost:41913/echo?user=alice&action=view → 200 OK

### Response Headers
```
content-length: 64
content-type: application/json
date: Fri, 11 Sep 2026 15:01:05 GMT
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
curl 'http://localhost:41913/echo?user=alice&action=view'
```
