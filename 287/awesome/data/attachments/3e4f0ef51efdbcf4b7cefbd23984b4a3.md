## GET http://localhost:41509/echo → 200 OK

### Request Headers
```
GET http://localhost:41509/echo
```

### Response Headers
```
content-length: 46
content-type: application/json
date: Fri, 27 Feb 2026 08:59:45 GMT
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
curl 'http://localhost:41509/echo'
```
