## GET http://localhost:46589/echo → 200 OK

### Request Headers
```
GET http://localhost:46589/echo
```

### Response Headers
```
content-length: 46
content-type: application/json
date: Thu, 26 Feb 2026 16:51:43 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "id=1"
}
```

### cURL
```bash
curl 'http://localhost:46589/echo'
```
