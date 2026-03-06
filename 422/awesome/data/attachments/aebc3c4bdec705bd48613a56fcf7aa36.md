## GET http://localhost:33251/echo → 200 OK

### Request Headers
```
GET http://localhost:33251/echo
```

### Response Headers
```
content-length: 73
content-type: application/json
date: Fri, 06 Mar 2026 22:31:28 GMT
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
curl 'http://localhost:33251/echo'
```
