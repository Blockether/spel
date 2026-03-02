## GET http://localhost:42265/echo → 200 OK

### Request Headers
```
GET http://localhost:42265/echo
```

### Response Headers
```
content-length: 73
content-type: application/json
date: Mon, 02 Mar 2026 14:15:11 GMT
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
curl 'http://localhost:42265/echo'
```
