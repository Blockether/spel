## GET http://localhost:45467/echo → 200 OK

### Request Headers
```
GET http://localhost:45467/echo
```

### Response Headers
```
content-length: 46
content-type: application/json
date: Thu, 05 Mar 2026 23:29:02 GMT
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
curl 'http://localhost:45467/echo'
```
