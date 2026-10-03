## DELETE http://localhost:41381/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 03 Oct 2026 14:55:53 GMT
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
curl 'http://localhost:41381/echo' \
  -X DELETE
```
