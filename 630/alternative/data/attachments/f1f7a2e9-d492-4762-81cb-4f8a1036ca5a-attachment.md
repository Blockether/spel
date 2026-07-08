## DELETE http://localhost:40093/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 08 Jul 2026 16:48:47 GMT
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
curl 'http://localhost:40093/echo' \
  -X DELETE
```
