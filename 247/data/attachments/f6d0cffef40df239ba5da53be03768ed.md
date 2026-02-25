## GET http://localhost:43433/echo → 200 OK

### Request Headers
```
GET http://localhost:43433/echo
```

### Response Headers
```
content-length: 73
content-type: application/json
date: Wed, 25 Feb 2026 10:39:25 GMT
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
curl 'http://localhost:43433/echo'
```
