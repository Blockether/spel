## GET http://localhost:39685/echo → 200 OK

### Request Headers
```
GET http://localhost:39685/echo
```

### Response Headers
```
content-length: 46
content-type: application/json
date: Tue, 03 Mar 2026 08:25:12 GMT
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
curl 'http://localhost:39685/echo'
```
