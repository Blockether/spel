## GET http://localhost:45931/echo?user=alice&action=view → 200 OK

### Request Headers
```
GET http://localhost:45931/echo?user=alice&action=view
```

### Response Headers
```
content-length: 64
content-type: application/json
date: Sat, 11 Apr 2026 09:37:13 GMT
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
curl 'http://localhost:45931/echo?user=alice&action=view'
```
