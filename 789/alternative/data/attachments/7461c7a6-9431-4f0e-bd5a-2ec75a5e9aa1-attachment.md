## DELETE http://localhost:38313/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 14 Oct 2026 13:23:26 GMT
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
curl 'http://localhost:38313/echo' \
  -X DELETE
```
