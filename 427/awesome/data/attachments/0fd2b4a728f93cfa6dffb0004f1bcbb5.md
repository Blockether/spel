## GET http://localhost:34543/echo → 200 OK

### Request Headers
```
GET http://localhost:34543/echo
```

### Response Headers
```
content-length: 46
content-type: application/json
date: Sat, 07 Mar 2026 07:00:11 GMT
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
curl 'http://localhost:34543/echo'
```
