## DELETE http://localhost:42375/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 14 Apr 2026 12:38:26 GMT
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
curl 'http://localhost:42375/echo' \
  -X DELETE
```
