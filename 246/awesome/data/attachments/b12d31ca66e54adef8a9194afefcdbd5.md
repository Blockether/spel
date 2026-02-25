## GET http://localhost:44173/echo → 200 OK

### Response Headers
```
content-length: 73
content-type: application/json
date: Wed, 25 Feb 2026 07:53:30 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "user=alice&action=view&limit=50"
}
```

### cURL
```bash
curl \
  'http://localhost:44173/echo'
```
