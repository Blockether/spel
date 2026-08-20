## DELETE http://localhost:44181/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Thu, 20 Aug 2026 12:40:11 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:44181/echo' \
  -X DELETE
```
