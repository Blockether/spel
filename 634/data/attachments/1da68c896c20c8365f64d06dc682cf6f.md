## DELETE http://localhost:35209/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 17 Jul 2026 15:42:43 GMT
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
curl 'http://localhost:35209/echo' \
  -X DELETE
```
