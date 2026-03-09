## GET http://localhost:38051/echo → 200 OK

### Request Headers
```
GET http://localhost:38051/echo
```

### Response Headers
```
content-length: 46
content-type: application/json
date: Mon, 09 Mar 2026 18:09:47 GMT
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
curl 'http://localhost:38051/echo'
```
