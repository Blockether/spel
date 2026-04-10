## GET http://localhost:38583/echo?user=alice&action=view → 200 OK

### Request Headers
```
GET http://localhost:38583/echo?user=alice&action=view
```

### Response Headers
```
content-length: 64
content-type: application/json
date: Fri, 10 Apr 2026 21:39:02 GMT
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
curl 'http://localhost:38583/echo?user=alice&action=view'
```
