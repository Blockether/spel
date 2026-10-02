## DELETE http://localhost:34799/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 02 Oct 2026 20:24:05 GMT
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
curl 'http://localhost:34799/echo' \
  -X DELETE
```
