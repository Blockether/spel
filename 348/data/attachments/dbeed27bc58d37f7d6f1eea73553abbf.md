## GET http://localhost:42577/echo → 200 OK

### Request Headers
```
GET http://localhost:42577/echo
```

### Response Headers
```
content-length: 46
content-type: application/json
date: Mon, 02 Mar 2026 10:58:56 GMT
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
curl 'http://localhost:42577/echo'
```
